/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.learning;

import com.forgeoj.api.problem.ProblemLibraryMapper;
import com.forgeoj.api.search.PublicSearchService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PracticeService {
  private final PracticeMapper mapper;
  private final ProblemLibraryMapper library;
  private final PublicSearchService search;

  public PracticeService(
      PracticeMapper mapper, ProblemLibraryMapper library, PublicSearchService search) {
    this.mapper = mapper;
    this.library = library;
    this.search = search;
  }

  public static String progress(String value) {
    if (value == null || value.isBlank() || value.equals("ALL")) return null;
    if (!Set.of("SOLVED", "ATTEMPTED", "UNTRIED").contains(value))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return value;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Page list(
      long userId,
      String keyword,
      String difficulty,
      String tag,
      String selected,
      int page,
      int size) {
    String progress = progress(selected),
        word = normalize(keyword, 100),
        level = normalize(difficulty, 8),
        label = normalize(tag, 32);
    if (page < 1
        || size < 1
        || size > 50
        || level != null && !Set.of("EASY", "MEDIUM", "HARD").contains(level))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    PublicSearchService.Page result;
    if (word != null)
      result = search.searchPersonal(word, level, label, page, size, userId, progress);
    else {
      var raw = titlePage(userId, null, level, label, progress, page, size);
      result = new PublicSearchService.Page(raw.items(), page, size, raw.total(), null, null);
    }
    Map<String, String> states = new HashMap<>();
    if (!result.items().isEmpty())
      mapper
          .states(userId, result.items().stream().map(PublicSearchService.Item::slug).toList())
          .forEach(s -> states.put(s.slug(), s.progress()));
    return new Page(
        result.items().stream()
            .map(
                p ->
                    new Item(
                        p.slug(),
                        p.title(),
                        p.difficulty(),
                        p.tags(),
                        p.judgeVersion(),
                        p.highlights(),
                        states.getOrDefault(p.slug(), "UNTRIED")))
            .toList(),
        page,
        size,
        result.total(),
        result.mode(),
        result.notice(),
        mapper.resume(userId).orElse(null));
  }

  public PublicSearchService.Page titlePage(
      long userId,
      String word,
      String difficulty,
      String tag,
      String progress,
      int page,
      int size) {
    String pattern =
        word == null
            ? null
            : "%" + word.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
    long total = mapper.count(userId, pattern, difficulty, tag, progress),
        offset = ((long) page - 1) * size;
    var rows =
        offset >= total
            ? List.<ProblemLibraryMapper.ProblemSummaryRow>of()
            : mapper.page(userId, pattern, difficulty, tag, progress, size, offset);
    Map<Long, List<String>> tags = new HashMap<>();
    if (!rows.isEmpty())
      library
          .tagsForProblems(rows.stream().map(ProblemLibraryMapper.ProblemSummaryRow::id).toList())
          .forEach(t -> tags.computeIfAbsent(t.problemId(), k -> new ArrayList<>()).add(t.tag()));
    return new PublicSearchService.Page(
        rows.stream()
            .map(
                p ->
                    new PublicSearchService.Item(
                        p.slug(),
                        p.title(),
                        p.difficulty(),
                        tags.getOrDefault(p.id(), List.of()),
                        p.judgeVersion(),
                        List.of()))
            .toList(),
        page,
        size,
        total,
        "TITLE_FALLBACK",
        PublicSearchService.NOTICE);
  }

  public PracticeMapper.Source source(long userId, String id) {
    if (id == null || !id.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return mapper
        .source(userId, id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private static String normalize(String value, int max) {
    if (value == null) return null;
    String s = value.strip();
    if (s.codePointCount(0, s.length()) > max || s.codePoints().anyMatch(Character::isISOControl))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    return s.isEmpty() ? null : s;
  }

  public record Item(
      String slug,
      String title,
      String difficulty,
      List<String> tags,
      int judgeVersion,
      List<List<PublicSearchService.Segment>> highlights,
      String progress) {}

  public record Page(
      List<Item> items,
      int page,
      int size,
      long total,
      String mode,
      String notice,
      PracticeMapper.Resume resume) {}
}
