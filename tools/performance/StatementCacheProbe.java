// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Own image, API role, SELECT constants only; never output credentials.
import java.nio.file.*;
import java.sql.*;
import com.mysql.cj.jdbc.ClientPreparedStatement;
import com.mysql.cj.jdbc.JdbcConnection;

class StatementCacheProbe {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("SPRING_DATASOURCE_URL");
        if(!url.startsWith("jdbc:mysql://mysql:3306/forgeoj?cachePrepStmts=")) throw new IllegalArgumentException();
        String password=Files.readString(Path.of("/run/secrets/spring.datasource.password")).strip();
        try(Connection c=DriverManager.getConnection(url,"forgeoj_api",password)) {
            c.setReadOnly(true);
            var p=c.unwrap(JdbcConnection.class).getPropertySet();
            boolean enabled=p.getBooleanProperty("cachePrepStmts").getValue();
            int size=p.getIntegerProperty("prepStmtCacheSize").getValue();
            int limit=p.getIntegerProperty("prepStmtCacheSqlLimit").getValue();
            boolean server=p.getBooleanProperty("useServerPrepStmts").getValue();
            if(size!=64||limit!=4096||server) throw new IllegalStateException("Unexpected connection properties");
            Object first,second;
            try(var s=c.prepareStatement("SELECT ? AS cache_probe")) {
                first=((ClientPreparedStatement)s).getQueryInfo();s.setInt(1,1);
                try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=1) throw new IllegalStateException();}
            }
            try(var s=c.prepareStatement("SELECT ? AS cache_probe")) {
                second=((ClientPreparedStatement)s).getQueryInfo();s.setInt(1,2);
                try(var r=s.executeQuery()){if(!r.next()||r.getInt(1)!=2) throw new IllegalStateException();}
            }
            if((first==second)!=enabled) throw new IllegalStateException("Parse cache behavior mismatch");
            String longSql="SELECT ? AS cache_probe /*"+"x".repeat(4100)+"*/";
            Object longFirst,longSecond;
            try(var s=c.prepareStatement(longSql)){longFirst=((ClientPreparedStatement)s).getQueryInfo();}
            try(var s=c.prepareStatement(longSql)){longSecond=((ClientPreparedStatement)s).getQueryInfo();}
            if(longFirst==longSecond) throw new IllegalStateException("SQL length bound ignored");
            System.out.printf("{\"cachePrepStmts\":%s,\"prepStmtCacheSize\":%d,\"prepStmtCacheSqlLimit\":%d,\"useServerPrepStmts\":%s,\"shortQueryInfoReused\":%s,\"differentParametersReturnDifferentValues\":true,\"overLimitQueryInfoReused\":false,\"probeConnectionIsSeparateFromApiPool\":true}%n",enabled,size,limit,server,first==second);
        }
    }
}
