package bake.example.provided;

import org.apache.commons.lang.StringUtils;
import tee.Tee;

/**
 * The foo jar is provided, meaning we can compile against it but it should not be included into
 * the fatJar.
 *
 * @author Justin Cummins (justin@squareup.com>
 */
public class Provided {
    public static void main(String[] args) throws ClassNotFoundException {
        Class.forName("Sleep");
        Tee.value.concat("");
        StringUtils.capitalize("heyoooo!");
    }
}
