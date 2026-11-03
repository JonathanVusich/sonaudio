package dev.javax.sonaudio.architecture;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;

/**
 * Keeps static helpers that take several values in utility classes, where each is tested on its own: a class of only
 * static methods, which can't be made, named for its job or {@code XxxUtils}. A static method of one parameter belongs
 * on that parameter's type, or is a small helper of its class.
 */
@AnalyzeClasses(packages = "dev.javax.sonaudio", importOptions = ImportOption.DoNotIncludeTests.class)
class StaticMethodRulesTest {

    @ArchTest
    static final ArchRule staticMethodsWithSeveralParametersAreInUtilityClasses = methods()
            .that()
            .areStatic()
            .and()
            .doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should(beInUtilityClassWhenTakingSeveralParameters())
            .allowEmptyShould(true);

    private static ArchCondition<JavaMethod> beInUtilityClassWhenTakingSeveralParameters() {
        return new ArchCondition<>("be in a utility class when taking several parameters") {
            @Override
            public void check(final JavaMethod method, final ConditionEvents events) {
                if (method.getRawParameterTypes().size() > 1 && !isUtilityClass(method.getOwner())) {
                    events.add(violated(method, method.getFullName() + " takes several parameters"));
                }
            }
        };
    }

    private static boolean isUtilityClass(final JavaClass owner) {
        return owner.getConstructors().stream()
                        .allMatch(constructor -> constructor.getModifiers().contains(JavaModifier.PRIVATE))
                && owner.getMethods().stream()
                        .allMatch(method -> method.getModifiers().contains(JavaModifier.STATIC));
    }
}
