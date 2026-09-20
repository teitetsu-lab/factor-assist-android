package jp.factor.overlay;
import java.math.BigInteger;

final class CycleLimit {
    static String validate(String text){BigInteger n=new BigInteger(text.trim());if(n.signum()<=0)throw new IllegalArgumentException("正の整数を入力してください");return n.toString();}
    static long intervalNanos(String text){BigInteger n=new BigInteger(validate(text));return BigInteger.valueOf(1_000_000_000L).add(n).subtract(BigInteger.ONE).divide(n).longValueExact();}
}
