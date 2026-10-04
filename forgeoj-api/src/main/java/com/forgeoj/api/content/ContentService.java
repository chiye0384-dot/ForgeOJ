/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.content;

import static com.forgeoj.api.content.ContentRecords.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import com.forgeoj.api.auth.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ContentService {
    private static final long MAX_VERSION=9007199254740991L;
    private final ContentMapper mapper;
    private final AccountService accounts;
    private final ObjectMapper json;
    public ContentService(ContentMapper mapper,AccountService accounts,ObjectMapper json) {this.mapper=mapper;this.accounts=accounts;this.json=json;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(long owner,int page,int size) {
        if(page<1 || size<1 || size>50) throw bad();long offset=((long)page-1)*size;long total=mapper.count(owner);
        return new Page(offset>=total?List.of():mapper.summaries(owner,size,offset),page,size,total);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(long owner,String id) { uuid(id);return detail(owner,mapper.find(owner,id).orElseThrow(ContentService::missing)); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<TestCase> tests(long owner,String id) {
        uuid(id);mapper.find(owner,id).orElseThrow(ContentService::missing);
        return mapper.tests(owner,id).stream().map(t -> new TestCase(t.sequence(),unzip(t.inputGzip(),t.inputBytes(),t.inputSha256()),unzip(t.outputGzip(),t.outputBytes(),t.outputSha256()))).toList();
    }
    @Transactional
    public Detail create(long owner,String title) {
        title=text(title,400,true).strip();if(title.codePointCount(0,title.length())>100) throw bad();
        accounts.requireCurrentWrite(owner);if(mapper.count(owner)>=100) throw conflict();
        var c=new Content(new Metadata(title,"","","",List.of(),"ORIGINAL","","",2000,256,1048576),"","","");
        String id=UUID.randomUUID().toString();mapper.insert(owner,id,c,json.writeValueAsString(c.metadata()));return detail(owner,id);
    }
    @Transactional
    public Detail save(long owner,String id,long version,Content c) {
        lock(owner,id,version);validate(c);
        changed(mapper.save(owner,id,version,c,json.writeValueAsString(c.metadata())));return detail(owner,id);
    }
    @Transactional
    public Detail replaceTests(long owner,String id,long version,List<TestDatasetArchive.TestPair> pairs) {
        lock(owner,id,version);
        if(pairs==null || pairs.size()>TestDatasetArchive.MAX_PAIRS) throw bad();
        List<ContentMapper.TestRow> rows=new ArrayList<>();long total=0;
        for(var pair:pairs) {
            if(pair==null) throw bad();String input=text(pair.input(),TestDatasetArchive.MAX_FILE_BYTES,false),output=text(pair.expectedOutput(),TestDatasetArchive.MAX_FILE_BYTES,false);
            byte[] in=input.getBytes(StandardCharsets.UTF_8),out=output.getBytes(StandardCharsets.UTF_8);total+=in.length+out.length;if(total>TestDatasetArchive.MAX_TOTAL_BYTES) throw bad();
            rows.add(new ContentMapper.TestRow(rows.size()+1,gzip(in),gzip(out),in.length,out.length,hash(in),hash(out)));
        }
        mapper.clearTests(owner,id);for(var row:rows) changed(mapper.insertTest(owner,id,row));changed(mapper.increment(owner,id,version));return detail(owner,id);
    }
    @Transactional
    public Detail archive(long owner,String id,long version) {lock(owner,id,version);changed(mapper.archive(owner,id,version));return detail(owner,id);}
    @Transactional
    public void delete(long owner,String id,long version) {
        lock(owner,id,version);
        if(mapper.validationReferences(id)>0 || mapper.reviewReferences(id)>0) throw conflict();
        mapper.clearTests(owner,id);changed(mapper.delete(owner,id,version));
    }
    private ContentMapper.Row lock(long owner,String id,long version) {
        uuid(id);if(version<1 || version>MAX_VERSION) throw bad();accounts.requireCurrentWrite(owner);
        var row=mapper.lock(owner,id).orElseThrow(ContentService::missing);
        if(row.version()!=version || row.version()==MAX_VERSION || !row.status().equals("DRAFT") || mapper.pendingReview(id).isPresent()) throw conflict();return row;
    }
    private Detail detail(long owner,ContentMapper.Row r) {return new Detail(new Summary(r.id(),r.title(),r.version(),r.status(),mapper.testCount(owner,r.id())),new Content(json.readValue(r.metadata(),Metadata.class),r.referenceCode(),r.solutionIdea(),r.solutionCode()));}
    static void validate(Content c) {
        if(c==null || c.metadata()==null) throw bad();var m=c.metadata();String title=text(m.title(),400,true);
        if(!title.equals(title.strip()) || title.codePointCount(0,title.length())>100) throw bad();
        text(m.statement(),65536,false);text(m.inputDescription(),16384,false);text(m.outputDescription(),16384,false);
        if(m.samples()==null || m.samples().size()>10) throw bad();for(var sample:m.samples()) {if(sample==null) throw bad();text(sample.input(),16384,false);text(sample.output(),16384,false);}
        if(!Set.of("ORIGINAL","ADAPTED").contains(m.originType())) throw bad();text(m.sourceUrl(),2000,false);text(m.licenseStatement(),8192,false);
        if(!m.sourceUrl().isEmpty()) {try {URI u=URI.create(m.sourceUrl());if(!Set.of("https","http").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null || u.getFragment()!=null) throw bad();}catch(IllegalArgumentException e) {throw bad();}}
        if(m.timeLimitMs()<100 || m.timeLimitMs()>30000 || m.memoryLimitMb()<64 || m.memoryLimitMb()>2048 || m.outputLimitBytes()<1 || m.outputLimitBytes()>16L*1024*1024) throw bad();
        text(c.referenceCode(),65536,false);text(c.solutionIdea(),65536,false);text(c.solutionCode(),65536,false);
    }
    private static String text(String s,int bytes,boolean required) {
        if(s==null || (required && s.isBlank()) || s.getBytes(StandardCharsets.UTF_8).length>bytes || s.indexOf('\0')>=0) throw bad();
        for(int i=0;i<s.length();i++) {char ch=s.charAt(i);if(Character.isHighSurrogate(ch)) {if(++i>=s.length() || !Character.isLowSurrogate(s.charAt(i))) throw bad();}else if(Character.isLowSurrogate(ch)) throw bad();}return s;
    }
    private static byte[] gzip(byte[] bytes) {try {var out=new ByteArrayOutputStream();try(var stream=new GZIPOutputStream(out)) {stream.write(bytes);}return out.toByteArray();}catch(IOException e) {throw new IllegalStateException("Test compression failed");}}
    private static String unzip(byte[] bytes,long expected,String digest) {
        if(expected<0 || expected>TestDatasetArchive.MAX_FILE_BYTES) throw new IllegalStateException("Invalid private test size");
        try(var stream=new GZIPInputStream(new ByteArrayInputStream(bytes))) {byte[] data=stream.readNBytes(TestDatasetArchive.MAX_FILE_BYTES+1);if(data.length!=expected || stream.read()!=-1 || !hash(data).equals(digest)) throw new IllegalStateException("Invalid private test integrity");return new String(data,StandardCharsets.UTF_8);}catch(IOException e) {throw new IllegalStateException("Invalid private test integrity");}
    }
    private static String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException("SHA-256 unavailable");}}
    private static void uuid(String id) {try {if(!UUID.fromString(id).toString().equals(id)) throw missing();}catch(IllegalArgumentException e) {throw missing();}}
    private static void changed(int count) {if(count!=1) throw conflict();}
    private static ResponseStatusException bad() {return new ResponseStatusException(HttpStatus.BAD_REQUEST);}
    private static ResponseStatusException missing() {return new ResponseStatusException(HttpStatus.NOT_FOUND);}
    private static ResponseStatusException conflict() {return new ResponseStatusException(HttpStatus.CONFLICT);}
}
