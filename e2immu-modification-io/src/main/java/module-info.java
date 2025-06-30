module org.e2immu.analyzer.modification.io {
    requires org.e2immu.language.cst.analysis;
    requires org.e2immu.language.cst.api;
    requires org.e2immu.language.cst.io;
    requires org.e2immu.language.inspection.api;
    requires org.e2immu.language.inspection.integration;
    requires org.e2immu.util.external.support;
    requires org.e2immu.util.internal.util;

    requires org.e2immu.analyzer.modification.common;
    requires org.e2immu.analyzer.modification.prepwork;
    requires org.slf4j;

    exports org.e2immu.analyzer.modification.io;
}