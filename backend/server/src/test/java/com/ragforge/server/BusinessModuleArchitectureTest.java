package com.ragforge.server;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Locks ADR-0014 business boundaries: no cross-business dependency on
 * {@code *.infrastructure.*Repository} types. Same-business Repository use remains allowed.
 * Cross-business persistence ports live in owning {@code application} packages.
 */
class BusinessModuleArchitectureTest {
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.ragforge.server");
    }

    @Test
    void sharedMustNotDependOnForeignInfrastructureRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ragforge.server.shared..")
                .should().dependOnClassesThat(
                        resideInAnyPackage(
                                "com.ragforge.server.space.infrastructure..",
                                "com.ragforge.server.knowledge.infrastructure..",
                                "com.ragforge.server.answer.infrastructure..")
                                .and(simpleNameEndingWith("Repository")))
                .because("shared must not reach into business infrastructure repositories");
        rule.check(classes);
    }

    @Test
    void spaceMustNotDependOnForeignInfrastructureRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ragforge.server.space..")
                .should().dependOnClassesThat(
                        resideInAnyPackage(
                                "com.ragforge.server.knowledge.infrastructure..",
                                "com.ragforge.server.answer.infrastructure..")
                                .and(simpleNameEndingWith("Repository")))
                .because("space must not import knowledge/answer infrastructure repositories");
        rule.check(classes);
    }

    @Test
    void knowledgeMustNotDependOnForeignInfrastructureRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ragforge.server.knowledge..")
                .should().dependOnClassesThat(
                        resideInAnyPackage(
                                "com.ragforge.server.space.infrastructure..",
                                "com.ragforge.server.answer.infrastructure..")
                                .and(simpleNameEndingWith("Repository")))
                .because("knowledge must not import space/answer infrastructure repositories");
        rule.check(classes);
    }

    @Test
    void answerMustNotDependOnForeignInfrastructureRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.ragforge.server.answer..")
                .should().dependOnClassesThat(
                        resideInAnyPackage(
                                "com.ragforge.server.space.infrastructure..",
                                "com.ragforge.server.knowledge.infrastructure..")
                                .and(simpleNameEndingWith("Repository")))
                .because("answer must not import space/knowledge infrastructure repositories");
        rule.check(classes);
    }
}
