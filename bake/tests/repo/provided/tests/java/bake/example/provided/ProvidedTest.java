package bake.example.provided;

import org.apache.commons.lang.StringUtils;
import org.junit.Test;
import tee.Tee;

import static org.junit.Assert.fail;

/**
 *
 */
public class ProvidedTest {
    @Test
    public void testProvidedAvailableInJava() {
        try {
            Provided.main(null);
        } catch (ClassNotFoundException expected) {
            fail();
        }
    }

    @Test
    public void testProvidedAvailableInTest() {
        try {
            Class.forName("Sleep");
            Tee.value.concat("");
            StringUtils.capitalize("heyoooo!");
        } catch (ClassNotFoundException expected) {
            fail();
        }
    }
}
