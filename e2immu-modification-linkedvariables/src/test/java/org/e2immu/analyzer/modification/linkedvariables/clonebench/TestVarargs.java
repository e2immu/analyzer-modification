package org.e2immu.analyzer.modification.linkedvariables.clonebench;

import org.e2immu.analyzer.modification.linkedvariables.CommonTest;
import org.e2immu.language.cst.api.info.Info;
import org.e2immu.language.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;


public class TestVarargs extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            import java.io.*;
            import java.net.*;
            import java.util.*;
            
            public class Function9706280_file1648039 {
            
                public static void displayInterfaceInformation(NetworkInterface netint) throws SocketException {
                    console.printf("Display name: %s%n", netint.getDisplayName());
                    console.printf("Name: %s%n", netint.getName());
                    Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
                    for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                        console.printf("InetAddress: %s%n", inetAddress);
                    }
                    console.printf("Parent: %s%n", netint.getParent());
                    console.printf("Up? %s%n", netint.isUp());
                    console.printf("Loopback? %s%n", netint.isLoopback());
                    console.printf("PointToPoint? %s%n", netint.isPointToPoint());
                    console.printf("Supports multicast? %s%n", netint.isVirtual());
                    console.printf("Virtual? %s%n", netint.isVirtual());
                    console.printf("Hardware address: %s%n", Arrays.toString(netint.getHardwareAddress()));
                    console.printf("MTU: %s%n", netint.getMTU());
                    List<InterfaceAddress> interfaceAddresses = netint.getInterfaceAddresses();
                    for (InterfaceAddress addr : interfaceAddresses) {
                        console.printf("InterfaceAddress: %s%n", addr.getAddress());
                    }
                    console.printf("%n");
                    Enumeration<NetworkInterface> subInterfaces = netint.getSubInterfaces();
                    for (NetworkInterface networkInterface : Collections.list(subInterfaces)) {
                        console.printf("%nSubInterface%n");
                        displayInterfaceInformation(networkInterface);
                    }
                    console.printf("%n");
                }
            
                public static final PrintStream console = System.out;
            }
            """;

    @DisplayName("varargs")
    @Test
    public void test1() {
        TypeInfo B = javaInspector.parse(INPUT1);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
    }

    @Language("java")
    private static final String INPUT2 = """
             import java.io.IOException;
             import java.nio.file.Files;
             import java.nio.file.Path;
             import java.util.stream.Stream;
            
             public class Slf4jEffort {
               private Stream<Path> getProjectsStream(Path root) throws IOException {
                 try (Stream<Path> s = Files.walk(root)) {
                   return s.filter(Files::isRegularFile);
                 }
               }
            }
            """;

    @DisplayName("method reference causes index out of bounds")
    @Test
    public void test2() {
        TypeInfo B = javaInspector.parse(INPUT2);
        List<Info> ao = prepWork(B);
        analyzer.doPrimaryType(B, ao);
    }
}
