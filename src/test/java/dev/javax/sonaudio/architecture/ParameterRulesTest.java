package dev.javax.sonaudio.architecture;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;

/**
 * Keeps flags out of signatures: a method that takes a boolean does two things, so it is two methods, and a
 * decision that is more than yes or no is an enum.
 */
@AnalyzeClasses(packages = "dev.javax.sonaudio", importOptions = ImportOption.DoNotIncludeTests.class)
class ParameterRulesTest {

    @ArchTest
    static final ArchRule noBooleanParameters =
            codeUnits().should(haveNoBooleanParameter()).allowEmptyShould(true);

    private static ArchCondition<JavaCodeUnit> haveNoBooleanParameter() {
        return new ArchCondition<>("have no boolean parameter") {
            @Override
            public void check(final JavaCodeUnit codeUnit, final ConditionEvents events) {
                for (final JavaClass parameter : codeUnit.getRawParameterTypes()) {
                    if (parameter.isEquivalentTo(boolean.class) || parameter.isEquivalentTo(Boolean.class)) {
                        events.add(violated(codeUnit, codeUnit.getDescription() + " takes a boolean"));
                    }
                }
            }
        };
    }
}
