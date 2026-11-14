/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.search;

import com.forgeoj.api.cache.CacheMapper;
import com.forgeoj.api.learning.PracticeMapper;
import com.forgeoj.api.learning.PracticeService;
import com.forgeoj.api.observability.MonitoringMetrics;
import com.forgeoj.api.problem.ProblemLibraryMapper;
import com.forgeoj.api.problem.ProblemLibraryService;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicSearchService {
  public static final String NOTICE = "正文搜索暂不可用，已按标题搜索；难度和标签筛选仍可使用。";

  public record Segment(String text, boolean matched) {}

  public record Item(
      String slug,
      String title,
      String difficulty,
      List<String> tags,
      int judgeVersion,
      List<List<Segment>> highlights) {}

  public record Page(
      List<Item> items, int page, int size, long total, String mode, String notice) {}

  private final SearchMapper mapper;
  private final CacheMapper epochs;
  private final ElasticSearchClient es;
  private final ProblemLibraryService library;
  private final TransactionTemplate snapshot;

  @Autowired private PracticeMapper practice;

  public PublicSearchService(
      SearchMapper mapper,
      CacheMapper epochs,
      ElasticSearchClient es,
      ProblemLibraryService library,
      PlatformTransactionManager manager) {
    this.mapper = mapper;
    this.epochs = epochs;
    this.es = es;
    this.library = library;
    snapshot = new TransactionTemplate(manager);
    snapshot.setReadOnly(true);
    snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  public Page search(String keyword, String difficulty, String tag, int page, int size) {
    return searchPersonal(keyword, difficulty, tag, page, size, 0, null);
  }

  public Page searchPersonal(
      String keyword,
      String difficulty,
      String tag,
      int page,
      int size,
      long userId,
      String progress) {
    progress = PracticeService.progress(progress);
    final String filter = progress;
    String word = normalize(keyword, 100),
        level = normalize(difficulty, 8),
        selected = normalize(tag, 32);
    if (word == null
        || page < 1
        || size < 1
        || size > 50
        || level != null && !Set.of("EASY", "MEDIUM", "HARD").contains(level)) throw invalid();
    try {
      long epoch = epochs.publicRevision();
      var control = mapper.control();
      if (control.activeJob() != null
          || control.readableEpoch() != epoch
          || control.indexUuid() == null) throw ElasticSearchClient.unavailable();
      var alias = es.alias();
      if (!alias.index().equals(control.indexName()) || !alias.uuid().equals(control.indexUuid()))
        throw ElasticSearchClient.unavailable();
      if (es.count(control.indexName()) != mapper.projectionCount())
        throw ElasticSearchClient.unavailable();
      // Preserve the existing MySQL collation semantics for exact tag filters.
      var matchingTags = selected == null ? null : mapper.matchingTags(selected);
      var candidates =
          matchingTags != null && matchingTags.isEmpty()
              ? List.<ElasticSearchClient.Candidate>of()
              : es.searchTags(word, level, matchingTags);
      Page result =
          snapshot.execute(
              s -> {
                if (epochs.publicRevision() != epoch) throw ElasticSearchClient.unavailable();
                var checked = new HashMap<Long, SearchMapper.Projection>();
                var tags = new HashMap<Long, List<String>>();
                for (int i = 0; i < candidates.size(); i += 50) {
                  var batch = candidates.subList(i, Math.min(i + 50, candidates.size()));
                  var ids = batch.stream().map(ElasticSearchClient.Candidate::problemId).toList();
                  for (var projection : mapper.projections(ids)) {
                    checked.put(projection.problemId(), projection);
                    tags.put(projection.problemId(), new ArrayList<>());
                  }
                  // Keep the same snapshot and tag ordering while removing one SQL call per hit.
                  for (var row : mapper.tagsForProblems(ids)) {
                    var problemTags = tags.get(row.problemId());
                    if (problemTags != null) problemTags.add(row.tag());
                  }
                }
                for (var candidate : candidates) {
                  var p = checked.get(candidate.problemId());
                  if (p == null
                      || !p.active()
                      || p.judgeVersion() < 1
                      || p.dataVersion() != candidate.dataVersion()
                      || !es.document(p, tags.get(p.problemId())).equals(candidate.document())
                      || level != null && !level.equals(p.difficulty())
                      || selected != null
                          && tags.get(p.problemId()).stream().noneMatch(matchingTags::contains))
                    throw ElasticSearchClient.unavailable();
                }
                var selectedCandidates = candidates;
                if (userId > 0 && filter != null && !candidates.isEmpty()) {
                  Map<String, String> states = new HashMap<>();
                  practice
                      .states(
                          userId,
                          candidates.stream().map(c -> checked.get(c.problemId()).slug()).toList())
                      .forEach(p -> states.put(p.slug(), p.progress()));
                  selectedCandidates =
                      candidates.stream()
                          .filter(c -> filter.equals(states.get(checked.get(c.problemId()).slug())))
                          .toList();
                }
                long offset = ((long) page - 1) * size;
                var items =
                    selectedCandidates.stream()
                        .skip(offset)
                        .limit(size)
                        .map(
                            c -> {
                              var p = checked.get(c.problemId());
                              return new Item(
                                  p.slug(),
                                  p.title(),
                                  p.difficulty(),
                                  tags.get(p.problemId()),
                                  p.judgeVersion(),
                                  highlights(p.title(), p.statementText(), word));
                            })
                        .toList();
                return new Page(items, page, size, selectedCandidates.size(), "FULL_TEXT", null);
              });
      if (epochs.publicRevision() != epoch) throw ElasticSearchClient.unavailable();
      var after = mapper.control();
      if (!Objects.equals(control, after)) throw ElasticSearchClient.unavailable();
      MonitoringMetrics.PROCESS.increment(MonitoringMetrics.Event.SEARCH_FULL_TEXT);
      return result;
    } catch (ElasticSearchClient.SearchUnavailable unavailable) {
      if (userId > 0) {
        String pattern = "%" + word.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
        long total = practice.count(userId, pattern, level, selected, filter),
            offset = ((long) page - 1) * size;
        var rows =
            offset >= total
                ? List.<ProblemLibraryMapper.ProblemSummaryRow>of()
                : practice.page(userId, pattern, level, selected, filter, size, offset);
        var tagMap = new HashMap<Long, List<String>>();
        if (!rows.isEmpty())
          for (var row :
              mapper.tagsForProblems(
                  rows.stream().map(ProblemLibraryMapper.ProblemSummaryRow::id).toList()))
            tagMap.computeIfAbsent(row.problemId(), k -> new ArrayList<>()).add(row.tag());
        return new Page(
            rows.stream()
                .map(
                    p ->
                        new Item(
                            p.slug(),
                            p.title(),
                            p.difficulty(),
                            tagMap.getOrDefault(p.id(), List.of()),
                            p.judgeVersion(),
                            highlights(p.title(), "", word)))
                .toList(),
            page,
            size,
            total,
            "TITLE_FALLBACK",
            NOTICE);
      }
      var fallback = library.list(word, level, selected, page, size);
      MonitoringMetrics.PROCESS.increment(MonitoringMetrics.Event.SEARCH_TITLE_FALLBACK);
      return new Page(
          fallback.items().stream()
              .map(
                  p ->
                      new Item(
                          p.slug(),
                          p.title(),
                          p.difficulty(),
                          p.tags(),
                          p.judgeVersion(),
                          highlights(p.title(), "", word)))
              .toList(),
          page,
          size,
          fallback.total(),
          "TITLE_FALLBACK",
          NOTICE);
    }
  }

  private static String normalize(String input, int limit) {
    if (input == null) return null;
    String s = input.strip();
    if (s.codePointCount(0, s.length()) > limit || s.codePoints().anyMatch(Character::isISOControl))
      throw invalid();
    return s.isEmpty() ? null : s;
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST);
  }

  static List<List<Segment>> highlights(String title, String body, String word) {
    var result = new ArrayList<List<Segment>>();
    for (String text : List.of(title, body)) {
      if (text.isEmpty()) continue;
      // Literal matches only, emitted as text nodes. Snippets never come from ES HTML.
      int match = text.indexOf(word),
          start = match < 0 ? 0 : Math.max(0, text.codePointCount(0, match) - 40);
      int begin = text.offsetByCodePoints(0, start),
          end =
              text.offsetByCodePoints(
                  begin, Math.min(160, text.codePointCount(begin, text.length())));
      String snippet = text.substring(begin, end);
      var segments = new ArrayList<Segment>();
      int at = 0, next;
      while ((next = snippet.indexOf(word, at)) >= 0) {
        if (next > at) segments.add(new Segment(snippet.substring(at, next), false));
        segments.add(new Segment(word, true));
        at = next + word.length();
      }
      if (at < snippet.length()) segments.add(new Segment(snippet.substring(at), false));
      result.add(List.copyOf(segments));
    }
    return List.copyOf(result);
  }
}
