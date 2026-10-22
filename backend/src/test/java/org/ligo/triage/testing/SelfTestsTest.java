package org.ligo.triage.testing;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

/** Runs the Tester agent's unit-level cases under JUnit so `mvn test` and the agent share one definition. */
class SelfTestsTest {
    @TestFactory
    Stream<DynamicTest> scoringUnitCases() {
        return SelfTests.unitCases().stream()
                .map(c -> DynamicTest.dynamicTest(c.area() + ": " + c.name(), () -> c.check().run()));
    }
}
