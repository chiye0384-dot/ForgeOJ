/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import static com.forgeoj.api.content.ContentRecords.*;
import java.io.IOException;
import java.util.*;
import com.forgeoj.api.auth.ForgeOjPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/me/authored-problems")
public class ContentController {
    private final ContentService service;
    private final ObjectMapper json;
    public ContentController(ContentService service,ObjectMapper json) {this.service=service;this.json=json;}
    @GetMapping ResponseEntity<Page> list(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {return result(service.list(p.userId(),page,size));}
    @PostMapping ResponseEntity<Detail> create(@AuthenticationPrincipal ForgeOjPrincipal p,@RequestBody Map<String,Object> b) {keys(b,"title");return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.create(p.userId(),string(b,"title")));}
    @GetMapping("/{id}") ResponseEntity<Detail> detail(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id) {return result(service.detail(p.userId(),id));}
    @PutMapping("/{id}") ResponseEntity<Detail> save(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {
        keys(b,"content","expectedVersion");if(!(b.get("content") instanceof Map<?,?> c)) throw bad();keys(c,"metadata","referenceCode","solutionIdea","solutionCode");
        if(!(c.get("metadata") instanceof Map<?,?> m)) throw bad();keys(m,"title","statement","inputDescription","outputDescription","samples","originType","sourceUrl","licenseStatement","timeLimitMs","memoryLimitMb","outputLimitBytes");
        if(!(m.get("samples") instanceof List<?> samples)) throw bad();for(var s:samples) {if(!(s instanceof Map<?,?> sm)) throw bad();keys(sm,"input","output");}
        // Prevent Jackson coercing strings/floats into resource-limit integers or text fields.
        for(String field:List.of("timeLimitMs","memoryLimitMb","outputLimitBytes")) integer(m.get(field));
        for(String field:List.of("title","statement","inputDescription","outputDescription","originType","sourceUrl","licenseStatement")) if(!(m.get(field) instanceof String)) throw bad();
        for(String field:List.of("referenceCode","solutionIdea","solutionCode")) if(!(c.get(field) instanceof String)) throw bad();
        for(var s:samples) {var sm=(Map<?,?>)s;if(!(sm.get("input") instanceof String) || !(sm.get("output") instanceof String)) throw bad();}
        Content content;try {content=json.convertValue(c,Content.class);}catch(IllegalArgumentException e) {throw bad();}return result(service.save(p.userId(),id,version(b),content));
    }
    @GetMapping("/{id}/tests") ResponseEntity<List<TestCase>> tests(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id) {return result(service.tests(p.userId(),id));}
    @PutMapping("/{id}/tests") ResponseEntity<Detail> tests(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {
        keys(b,"tests","expectedVersion");if(!(b.get("tests") instanceof List<?> tests) || tests.size()>TestDatasetArchive.MAX_PAIRS) throw bad();
        var pairs=new ArrayList<TestDatasetArchive.TestPair>();for(var entry:tests) {if(!(entry instanceof Map<?,?> t)) throw bad();keys(t,"input","expectedOutput");if(!(t.get("input") instanceof String in) || !(t.get("expectedOutput") instanceof String out)) throw bad();pairs.add(new TestDatasetArchive.TestPair(pairs.size()+1,in,out));}
        return result(service.replaceTests(p.userId(),id,version(b),pairs));
    }
    @PutMapping(value="/{id}/tests/zip",consumes="application/zip") ResponseEntity<Detail> zip(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestParam long expectedVersion,HttpServletRequest request) throws IOException {
        // Author ownership checked before parsing the private upload; ContentService rechecks
        // current session/version under lock when persisting the fully parsed dataset.
        service.detail(p.userId(),id);if(request.getContentLengthLong()>TestDatasetArchive.MAX_ARCHIVE_BYTES) throw bad();
        byte[] bytes=request.getInputStream().readNBytes(TestDatasetArchive.MAX_ARCHIVE_BYTES+1);if(bytes.length>TestDatasetArchive.MAX_ARCHIVE_BYTES) throw bad();
        List<TestDatasetArchive.TestPair> pairs;try {pairs=TestDatasetArchive.parse(bytes);}catch(IllegalArgumentException e) {throw bad();}
        return result(service.replaceTests(p.userId(),id,expectedVersion,pairs));
    }
    @PostMapping("/{id}/archive") ResponseEntity<Detail> archive(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestBody Map<String,Object> b) {keys(b,"expectedVersion");return result(service.archive(p.userId(),id,version(b)));}
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@AuthenticationPrincipal ForgeOjPrincipal p,@PathVariable String id,@RequestParam long expectedVersion) {service.delete(p.userId(),id,expectedVersion);return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
    @ExceptionHandler({DataAccessException.class,IllegalStateException.class}) ResponseEntity<Void> unavailable() {return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();}
    private static <T> ResponseEntity<T> result(T t) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(t);}
    private static String string(Map<String,Object> b,String field) {if(!(b.get(field) instanceof String value)) throw bad();return value;}
    private static void keys(Map<?,?> b,String... fields) {if(!b.keySet().equals(Set.of(fields))) throw bad();}
    private static long integer(Object v) {if(!(v instanceof Integer || v instanceof Long)) throw bad();return ((Number)v).longValue();}
    private static long version(Map<String,Object> b) {return integer(b.get("expectedVersion"));}
    private static ResponseStatusException bad() {return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
}
