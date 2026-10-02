package com.forgeoj.api.auth;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Development simulator. Never log mail or expose it through the ordinary API. */
@Component
public class LocalAccountMail implements AccountMailDelivery {
    public record Mail(String recipient, String purpose, String link) {}
    private final ArrayDeque<Mail> messages = new ArrayDeque<>();
    private final String baseUrl;
    private final HttpServer server;
    public LocalAccountMail(Environment environment,
            @Value("${forgeoj.auth.mail.mode:disabled}") String mode,
            @Value("${forgeoj.auth.mail.port:2525}") int port,
            @Value("${forgeoj.auth.mail.app-url:http://localhost:5173}") String baseUrl) throws IOException {
        this.baseUrl = baseUrl;
        if ("local".equals(mode)) {
            if (!environment.matchesProfiles("dev", "test")) throw new IllegalStateException("Local mailbox requires dev/test profile");
            URI uri=URI.create(baseUrl);
            if (!List.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalStateException("Invalid mail application URL");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),8);
            server.createContext("/", exchange -> {
                if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); exchange.close(); return; }
                StringBuilder body=new StringBuilder("<!doctype html><meta charset=utf-8><title>ForgeOJ 本地邮件</title><h1>开发邮件模拟器</h1><p>仅隔离开发数据，关闭进程后清空。</p>");
                synchronized(messages) { for(Mail mail:messages) body.append("<article><p>").append(escape(mail.recipient())).append(" · ").append(escape(mail.purpose())).append("</p><a href=\"").append(escape(mail.link())).append("\">打开验证页面</a></article>"); }
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control","no-store");
                exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
                exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
                exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()) {out.write(bytes);} finally {exchange.close();}
            }); server.start();
        } else if ("disabled".equals(mode)) server=null;
        else throw new IllegalStateException("Unsupported mail mode; real SMTP is not configured");
    }
    @Override
    public void send(String recipient,String purpose,String token) {
        if(server==null) throw new IllegalStateException("Account mail delivery is disabled");
        String action=switch(purpose){ case "ACTIVATE"->"activate"; case "RESET_PASSWORD"->"reset"; case "BIND_EMAIL"->"bind"; default->throw new IllegalArgumentException("Unknown mail purpose"); };
        synchronized(messages) { if(messages.size()==100) messages.removeLast(); messages.addFirst(new Mail(recipient,purpose,baseUrl+"/account#action="+action+"&token="+token)); }
    }
    public List<Mail> messages(){ synchronized(messages){return List.copyOf(messages);} }
    public int port(){return server==null?-1:server.getAddress().getPort();}
    private static String escape(String text){return text.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    @PreDestroy void close(){ if(server!=null) server.stop(0); synchronized(messages){messages.clear();} }
}
