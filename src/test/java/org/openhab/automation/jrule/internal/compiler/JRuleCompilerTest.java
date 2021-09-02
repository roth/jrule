package org.openhab.automation.jrule.internal.compiler;

import static org.openhab.automation.jrule.internal.JRuleConfig.RULES_PACKAGE;

import java.io.File;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class JRuleCompilerTest {

    @Test
    public void ensureProperClassNameDerivation() {
        File exampleClassFile = new File("/etc/openhab/automation/jrule/rule-classes/org/openhab"
                + "/automation/jrule/rules/user/custom/package/MySuperDuper.class");
        String qualifiedClassName = JRuleCompiler.convertToClassName(exampleClassFile, RULES_PACKAGE);

        Assertions.assertEquals("org.openhab.automation.jrule.rules.user.custom.package.MySuperDuper",
                qualifiedClassName);
    }
}
