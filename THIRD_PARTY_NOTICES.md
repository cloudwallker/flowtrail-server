# Third-party components

FlowTrail Server's own source is licensed under MIT. Runtime dependencies retain their respective licenses; this license does not replace them.

The executable is a Spring Boot archive. Original dependency JARs are retained under `BOOT-INF/lib/`, including their upstream license and notice resources. The `licenses/` directory contains a generated inventory and copies of license/notice resources found in these JARs. Test dependencies and Lombok are not bundled.

Major components include Spring Boot / Spring Framework (Apache-2.0), Jackson (Apache-2.0), Apache Tomcat (Apache-2.0), H2 (MPL-2.0 or EPL-1.0), HikariCP (Apache-2.0), Hibernate Validator (Apache-2.0), SLF4J (MIT), and Logback (EPL-1.0 or LGPL-2.1).
Workflow HTTP nodes additionally use Apache HttpClient 5 (Apache-2.0) to explicitly disable automatic retries.

Version 0.2 adds Spring AI (Apache-2.0), Flyway core and MySQL support (Apache-2.0), and MySQL Connector/J 9.6.0 (GPL-2.0 with Universal FOSS Exception 1.0). The unchanged driver JAR and its full upstream license remain bundled; the project's MIT license does not replace dependency terms.

Review dependency licenses and refresh the inventory when upgrading. Official references:
- https://spring.io/projects/spring-boot
- https://www.h2database.com/html/license.html
- https://logback.qos.ch/license.html
