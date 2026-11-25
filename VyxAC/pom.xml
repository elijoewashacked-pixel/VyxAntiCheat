<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.vyxac</groupId>
    <artifactId>VyxAC</artifactId>
    <version>1.2.0</version>
    <packaging>jar</packaging>
    <name>VyxAC</name>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <repositories>
        <repository>
            <id>dmulloy2-repo</id>
            <url>https://repo.dmulloy2.net/repository/public/</url>
        </repository>
        <repository>
            <id>spigot-repo</id>
            <url>https://hub.spigotmc.org/nexus/content/repositories/snapshots/</url>
        </repository>
    </repositories>

    <dependencies>
        <!-- Compile against the API of the server version you run (1.20.x / 1.21.x). Works on Spigot, Paper, Purpur. -->
        <dependency>
            <groupId>org.spigotmc</groupId>
            <artifactId>spigot-api</artifactId>
            <version>1.20.4-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
        <!-- needed ONLY for the CheckHacks sign translation check (installed on the server as a plugin) -->
        <dependency>
            <groupId>com.comphenix.protocol</groupId>
            <artifactId>ProtocolLib</artifactId>
            <version>5.3.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>VyxAC-${project.version}</finalName>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.11.0</version>
            </plugin>
        </plugins>
    </build>
</project>
