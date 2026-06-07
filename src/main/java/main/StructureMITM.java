package main;

import com.seedfinding.mccore.rand.seed.RegionSeed;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.LongStream;

public class StructureMITM {

    static final long LCG_A   = 0x5deece66dL;
    static final long LCG_B   = 0xbL;
    static final long MASK_48 = (1L << 48) - 1;
    static final long MASK_24 = (1L << 24) - 1;

    public static int LUT_BITS = 24;

    public record Constraint(long salt, int targetX, int targetZ, int spacing) {
        public static Constraint of(int structureSalt, int spacing, int chunkX, int chunkZ) {
            int regionX = Math.floorDiv(chunkX, spacing);
            int regionZ = Math.floorDiv(chunkZ, spacing);
            long salt = ((long) regionX * RegionSeed.A
                       + (long) regionZ * RegionSeed.B
                       + structureSalt) & MASK_48;
            return new Constraint(salt, chunkX - regionX * spacing, chunkZ - regionZ * spacing, spacing);
        }
    }
    private final Constraint[] cs;
    private final int n;

    private final int[] resultCarry;

    private Object[][] tries;

    public static boolean verbose = true;

    public StructureMITM(List<Constraint> constraints) {
        this.cs = constraints.toArray(new Constraint[0]);
        this.n  = cs.length;
        this.resultCarry = new int[n];
        for (int i = 0; i < n; i++) {
            resultCarry[i] = (int) modPow(2L, 31, cs[i].spacing());
        }
    }

    public List<Long> findSeeds() {
        buildLUT();
        return searchUppers();
    }

    public boolean verify(long seed) {
        for (Constraint c : cs) {
            long s0 = ((seed + c.salt()) ^ LCG_A) & MASK_48;
            long s1 = (LCG_A * s0 + LCG_B) & MASK_48;
            long s2 = (LCG_A * s1 + LCG_B) & MASK_48;
            if ((int)(s1 >> 17) % c.spacing() != c.targetX()) return false;
            if ((int)(s2 >> 17) % c.spacing() != c.targetZ()) return false;
        }
        return true;
    }

    private void buildLUT() {
        tries = new Object[1 << n][];

        long[] saltLow = new long[n];
        for (int i = 0; i < n; i++) saltLow[i] = cs[i].salt() & MASK_24;

        long total = 1L << LUT_BITS;
        if (verbose) System.out.printf("[MITM] Building LUT: %,d lower values, %d constraints%n", total, n);
        long t0 = System.currentTimeMillis();

        for (int lower = 0; lower < total; lower++) {
            insertLower(lower, saltLow);
        }

        if (verbose) System.out.printf("[MITM] LUT ready in %.1f s%n",
            (System.currentTimeMillis() - t0) / 1000.0);
    }

    private void insertLower(int lower, long[] saltLow) {
        int carryBits = 0;
        int[] targets = new int[2 * n];

        for (int i = 0; i < n; i++) {
            long sum = lower + saltLow[i];
            if (sum > MASK_24) carryBits |= (1 << i);

            long s0low = (sum ^ LCG_A) & MASK_24;

            long s1 = (LCG_A * s0low + LCG_B) & MASK_48;
            long s2 = (LCG_A * s1       + LCG_B) & MASK_48;

            int sp = cs[i].spacing();

            targets[2*i  ] = Math.floorMod(cs[i].targetX() - (int)(s1 >> 17), sp);
            targets[2*i+1] = Math.floorMod(cs[i].targetZ() - (int)(s2 >> 17), sp);
        }

        if (tries[carryBits] == null) tries[carryBits] = new Object[cs[0].spacing()];
        insertTrie(tries[carryBits], targets, 0, lower);
    }

    private void insertTrie(Object[] node, int[] targets, int depth, int lower) {
        int t = targets[depth];
        if (depth == 2 * n - 1) {

            int[] cur = (int[]) node[t];
            if (cur == null) {
                node[t] = new int[]{ lower };
            } else {
                int[] next = Arrays.copyOf(cur, cur.length + 1);
                next[cur.length] = lower;
                node[t] = next;
            }
        } else {
            if (node[t] == null) node[t] = new Object[spacingAt(depth + 1)];
            insertTrie((Object[]) node[t], targets, depth + 1, lower);
        }
    }

    private int spacingAt(int depth) {
        return cs[depth / 2].spacing();
    }

    private List<Long> searchUppers() {
        int upperBits  = 48 - LUT_BITS;
        long upperCount = 1L << upperBits;

        long[] saltHigh = new long[n];
        for (int i = 0; i < n; i++) saltHigh[i] = (cs[i].salt() >> LUT_BITS) & MASK_24;

        if (verbose) System.out.printf("[MITM] Sweeping %,d upper values (parallel)...%n", upperCount);
        long t0 = System.currentTimeMillis();

        ConcurrentLinkedQueue<Long> results = new ConcurrentLinkedQueue<>();

        ThreadLocal<int[]> localSig = ThreadLocal.withInitial(() -> new int[2 * n]);

        LongStream.range(0, upperCount).parallel().forEach(upper -> {
            int[] sig = localSig.get();
            int  iUpper = (int) upper;

            for (int carryBits = 0; carryBits < (1 << n); carryBits++) {
                if (tries[carryBits] == null) continue;
                computeUpperSig(iUpper, carryBits, saltHigh, sig);
                traverseTrie(tries[carryBits], sig, 0, iUpper, results);
            }
        });

        List<Long> list = new ArrayList<>(results);
        if (verbose) System.out.printf("[MITM] Done in %.1f s — %d candidates (before verify)%n",
            (System.currentTimeMillis() - t0) / 1000.0, list.size());
        return list;
    }

    private void computeUpperSig(int upper, int carryBits, long[] saltHigh, int[] sig) {
        for (int i = 0; i < n; i++) {
            int  carry   = (carryBits >> i) & 1;
            long s0high  = ((carry + saltHigh[i] + upper) & MASK_24) ^ (LCG_A >> LUT_BITS);
            long s0upper = s0high << LUT_BITS;           // lower LUT_BITS are 0
            long s1      = (LCG_A * s0upper) & MASK_48;  // no +LCG_B
            long s2      = (LCG_A * s1      ) & MASK_48;
            int sp = cs[i].spacing();
            sig[2*i  ] = (int)((s1 >> 17) % sp);
            sig[2*i+1] = (int)((s2 >> 17) % sp);
        }
    }

    private void traverseTrie(Object[] node, int[] sig, int depth,
                               int upper, ConcurrentLinkedQueue<Long> results) {
        int sp = spacingAt(depth);
        int rc = resultCarry[depth / 2];

        int idx1 = sig[depth];
        int idx2 = Math.floorMod(sig[depth] - rc, sp);

        Object child1 = node[idx1];
        Object child2 = (idx2 != idx1) ? node[idx2] : null;

        for (int pass = 0; pass < 2; pass++) {
            Object child = (pass == 0) ? child1 : child2;
            if (child == null) continue;

            if (depth == 2 * n - 1) {
                for (int lower : (int[]) child) {
                    results.add(((long) upper << LUT_BITS) | (lower & 0xFF_FFFFL));
                }
            } else {
                traverseTrie((Object[]) child, sig, depth + 1, upper, results);
            }
        }
    }

    private static long modPow(long base, long exp, long mod) {
        long result = 1;
        base %= mod;
        while (exp > 0) {
            if ((exp & 1) == 1) result = result * base % mod;
            base = base * base % mod;
            exp >>= 1;
        }
        return result;
    }
}
