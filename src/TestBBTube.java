/** Self-check for the app's text helpers; build.sh runs it on the desktop JVM. */
public class TestBBTube {
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        check(BBTube.urlEncode("rick astley").equals("rick%20astley"), "space");
        check(BBTube.urlEncode("a;b&c=d").equals("a%3Bb%26c%3Dd"), "semicolon must not reach the URL");
        check(BBTube.urlEncode("café").equals("caf%C3%A9"), "utf-8");
        check(BBTube.urlEncode("A-z_0.~").equals("A-z_0.~"), "unreserved kept");

        String[] f = BBTube.split("id\t3:34\tTitle\t", '\t');
        check(f.length == 4 && f[2].equals("Title") && f[3].equals(""), "split keeps empty fields");
        check(BBTube.split("", '\t').length == 1, "split of empty string");

        String[] l = BBTube.lines("a\nb\tc\n\n".getBytes("UTF-8"));
        check(l.length == 2 && l[1].equals("b\tc"), "lines drops trailing blanks");
        check(BBTube.lines(new byte[0]).length == 0, "empty response");
        check(BBTube.mix(0x000000, 0xffffff, 1, 2) == 0x7f7f7f && BBTube.mix(0x123456, 0xabcdef, 0, 9) == 0x123456, "mix");
        check(BBTube.mix(0x123456, 0xabcdef, 9, 9) == 0xabcdef, "mix end");
        check(BBTube.seconds("4:57") == 297 && BBTube.seconds("1:02:05") == 3725, "seconds");
        check(BBTube.seconds("live") == 0 && BBTube.seconds("") == 0, "seconds of a non-length");
        check(BBTube.clock(297).equals("4:57") && BBTube.clock(3725).equals("1:02:05"), "clock");
        check(BBTube.clock(0).equals("0:00") && BBTube.clock(65).equals("1:05"), "clock pads seconds");
        System.out.println("app helpers ok");
    }
}
