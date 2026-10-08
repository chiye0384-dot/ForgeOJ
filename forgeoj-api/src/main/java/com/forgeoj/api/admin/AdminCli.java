/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
package com.forgeoj.api.admin;

import java.sql.DriverManager;
import java.util.Arrays;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Routes before Spring starts; credentials never appear in args, output or request JSON. */
public final class AdminCli {
    private AdminCli() {}
    public static int run(String[] args){
        if(args.length!=1||!java.util.Set.of("admin-bootstrap","admin-recover").contains(args[0])){System.err.println("Use admin-bootstrap or admin-recover without additional arguments.");return 2;}
        var console=System.console();if(console==null){System.err.println("Interactive console required; no Web services were started.");return 2;}
        char[] first=null,second=null;
        try{
            String url=System.getenv("FORGEOJ_ADMIN_CLI_DB_URL"),user=System.getenv("FORGEOJ_ADMIN_CLI_DB_USERNAME"),dbPassword=System.getenv("FORGEOJ_ADMIN_CLI_DB_PASSWORD");
            if(url==null||!url.startsWith("jdbc:mysql://")||url.toLowerCase(java.util.Locale.ROOT).matches(".*(?:password|user)=.*")||user==null||dbPassword==null)throw new IllegalStateException();
            try(var connection=DriverManager.getConnection(url,user,dbPassword)){
                String catalog=connection.getCatalog();String confirmation=console.readLine("Confirm target database '%s' by typing its name: ",catalog);if(!catalog.equals(confirmation))throw new IllegalStateException();
                boolean bootstrap=args[0].equals("admin-bootstrap");String name=null,reason=null;long id=0;
                if(bootstrap)name=console.readLine("First administrator username: ");else{if(!"RECOVER".equals(console.readLine("Confirm all SUPER_ADMIN accounts cannot log in; type RECOVER: ")))throw new IllegalStateException();id=Long.parseLong(console.readLine("Existing SUPER_ADMIN ID: "));reason=console.readLine("Recovery reason: ");}
                first=console.readPassword("New password: ");second=console.readPassword("Repeat new password: ");if(first==null||second==null||!Arrays.equals(first,second))throw new IllegalStateException();
                long target=bootstrap?AdminProvisioning.bootstrap(connection,name,new String(first),new BCryptPasswordEncoder()):AdminProvisioning.recover(connection,id,new String(first),reason,new BCryptPasswordEncoder());
                console.printf("Administrator %d updated with durable audit. First-login password change is required.%n",target);return 0;
            }
        }catch(Exception failure){System.err.println("Administrator maintenance refused or unavailable; no credential details are logged.");return 1;}finally{if(first!=null)Arrays.fill(first,'\0');if(second!=null)Arrays.fill(second,'\0');}
    }
}
