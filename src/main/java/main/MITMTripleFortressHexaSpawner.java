package main;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.RegionSeed;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.version.MCVersion;
import fortressGen.FortressGenerator;
import fortressGen.FortressGenerator.PieceInfo;

import java.util.ArrayList;
import java.util.List;

public class MITMTripleFortressHexaSpawner {

    static final MCVersion VERSION     = MCVersion.v1_16_1;
    static final int       STRUCT_SALT = 30084232;
    static final int       SPACING     = 27;
    static final int       OFFSET      = 23;

    static final long LCG_A  = 0x5deece66dL;
    static final long LCG_B  = 0xbL;
    static final long MASK48 = (1L << 48) - 1;

    static final int START_RADIUS  = 0;
    static final int SEARCH_RADIUS = 10;
    static final int MAX_DIST      = 32;

    static final int CHUNK_OFFSET = 1;

    static final int[][] TRIPLES      = {{0,1,2},{0,1,3},{0,2,3},{1,2,3}};
    static final String[] MISSING_LBL = {"(rx+1,rz+1)","(rx,rz+1)","(rx+1,rz)","(rx,rz)"};

    static final int[] PERF_X = { OFFSET-1, 0,        OFFSET-1, 0 };
    static final int[] PERF_Z = { OFFSET-1, OFFSET-1, 0,        0 };

    static final int[] DIR_X = { -1, +1, -1, +1 };
    static final int[] DIR_Z = { -1, -1, +1, +1 };

    static final int[] BASE_X = {0, 1, 0, 1};
    static final int[] BASE_Z = {0, 0, 1, 1};

    static final int[][] ORIGIN_REG = {{-1,-1},{0,-1},{-1,0},{0,0}};

    // ─────────────────────────────────────────────────────────────────────────

    static final class MitmRun {
        final int     configIdx;
        final int[]   triple;
        final int[]   ox = new int[4];
        final int[]   oz = new int[4];
        long[]        seeds;

        MitmRun(int ci, int[] triple) {
            this.configIdx = ci;
            this.triple    = triple;
            for (int fi = 0; fi < 4; fi++) { ox[fi] = PERF_X[fi]; oz[fi] = PERF_Z[fi]; }
        }

        String offsetDesc() {
            StringBuilder sb = new StringBuilder();
            for (int fi : triple) {
                boolean perfX = (ox[fi] == PERF_X[fi]);
                boolean perfZ = (oz[fi] == PERF_Z[fi]);
                if (!perfX || !perfZ)
                    sb.append(String.format("f%d@(%d,%d) ", fi, ox[fi], oz[fi]));
            }
            return sb.length() == 0 ? "perfect" : sb.toString().trim();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        StructureMITM.verbose = false;

        List<MitmRun> runs = new ArrayList<>();
        for (int ci = 0; ci < 4; ci++) {
            int[] triple = TRIPLES[ci];
            int n        = triple.length;
            int opts     = CHUNK_OFFSET + 1;
            int variants = 1;
            for (int k = 0; k < 2 * n; k++) variants *= opts;

            for (int v = 0; v < variants; v++) {
                MitmRun run = new MitmRun(ci, triple);
                int tmp = v;
                for (int k = 0; k < n; k++) {
                    int fi   = triple[k];
                    int xOff = tmp % opts;  tmp /= opts;
                    int zOff = tmp % opts;  tmp /= opts;
                    run.ox[fi] = PERF_X[fi] + xOff * DIR_X[fi];
                    run.oz[fi] = PERF_Z[fi] + zOff * DIR_Z[fi];
                }
                runs.add(run);
            }
        }
        System.out.printf("CHUNK_OFFSET=%d  →  total MITM runs: %d%n", CHUNK_OFFSET, runs.size());

        long totalSeeds = 0;
        for (int i = 0; i < runs.size(); i++) {
            MitmRun run = runs.get(i);

            List<StructureMITM.Constraint> constraints = new ArrayList<>();
            for (int fi : run.triple) {
                int[] reg = ORIGIN_REG[fi];
                constraints.add(new StructureMITM.Constraint(
                    regionSalt(reg[0], reg[1]), run.ox[fi], run.oz[fi], OFFSET));
            }

            StructureMITM mitm     = new StructureMITM(constraints);
            List<Long>    cands    = mitm.findSeeds();
            final MitmRun frun     = run;
            run.seeds = cands.stream()
                .filter(s -> verifyTriple(s, -1, -1, frun))
                .distinct().sorted()
                .mapToLong(Long::longValue).toArray();
            totalSeeds += run.seeds.length;

            if ((i + 1) % 32 == 0 || (i + 1) == runs.size()) {
                System.out.printf("  [%4d/%4d]  run seeds: %6d  total: %d%n",
                    i + 1, runs.size(), run.seeds.length, totalSeeds);
            }
        }
        System.out.printf("%nAll MITMs done — %d total seeds across %d runs%n%n",
            totalSeeds, runs.size());

        for (int ring = START_RADIUS; ring < SEARCH_RADIUS; ring++) {
            final List<MitmRun> fRuns = runs;
            getRingBlocks(ring).parallelStream().forEach(block -> {
                int rx = block[0], rz = block[1];
                long delta = (-(long)(rx+1)*RegionSeed.A - (long)(rz+1)*RegionSeed.B) & MASK48;

                ChunkRand        rand    = new ChunkRand();
                FortressGenerator fortGen = new FortressGenerator(VERSION);

                for (MitmRun run : fRuns) {
                    int[] cxArr = new int[4], czArr = new int[4];
                    for (int fi = 0; fi < 4; fi++) {
                        cxArr[fi] = (rx + BASE_X[fi]) * SPACING + run.ox[fi];
                        czArr[fi] = (rz + BASE_Z[fi]) * SPACING + run.oz[fi];
                    }
                    for (long originSeed : run.seeds) {
                        long seed = (originSeed + delta) & MASK48;
                        checkSeed(seed, rx, rz, run, cxArr, czArr, rand, fortGen);
                    }
                }
            });
            System.out.printf("done ring %d%n", ring);
        }
    }

    private static void checkSeed(long structureSeed, int rx, int rz,
                                   MitmRun run, int[] cxArr, int[] czArr,
                                   ChunkRand rand, FortressGenerator fortGen) {

        int[] triple = run.triple;

        ArrayList<BPos> xzSpawners = new ArrayList<>(6);
        for (int fi : triple) {
            List<BPos> fast = fortGen.fastGenerateSpawners(structureSeed, cxArr[fi], czArr[fi], rand);
            if (fast.size() < 2) return;
            for (BPos newS : fast) {
                for (BPos existing : xzSpawners) {
                    int dx = newS.getX() - existing.getX();
                    int dz = newS.getZ() - existing.getZ();
                    if (dx*dx + dz*dz > MAX_DIST * MAX_DIST) return;
                }
                xzSpawners.add(newS);
            }
        }

        ArrayList<BPos> spawners = new ArrayList<>(6);
        for (int fi : triple) {
            fortGen.generate(structureSeed, cxArr[fi], czArr[fi], rand);
            for (List<PieceInfo> pieceList : fortGen.getPlacements()) {
                for (PieceInfo piece : pieceList) {
                    if (piece.type != 5) continue;
                    BPos newS = piece.getSpawnerPos();
                    for (BPos existing : spawners) {
                        int dx = newS.getX() - existing.getX();
                        int dy = newS.getY() - existing.getY();
                        int dz = newS.getZ() - existing.getZ();
                        if (dx*dx + dy*dy + dz*dz > MAX_DIST * MAX_DIST) return;
                    }
                    spawners.add(newS);
                }
            }
        }

        if (spawners.size() < 6) return;

        System.out.println(structureSeed + " " + rx + " " + rz
            + "  [missing: " + MISSING_LBL[run.configIdx]
            + "  " + run.offsetDesc() + "]");
        System.out.printf("/tp @p %d 70 %d%n", (rx+1)*SPACING*16, (rz+1)*SPACING*16);
        System.out.printf("6 spawners all within %d blocks:%n", MAX_DIST);
        for (BPos s : spawners) {
            System.out.printf("  /tp @p %d %d %d%n", s.getX(), s.getY(), s.getZ());
        }
        System.out.println();
        System.out.flush();
    }

    static boolean verifyTriple(long seed, int rx, int rz, MitmRun run) {
        int[][] reg = {{rx,rz},{rx+1,rz},{rx,rz+1},{rx+1,rz+1}};
        for (int fi : run.triple) {
            long salt = regionSalt(reg[fi][0], reg[fi][1]);
            long s0 = ((seed + salt) ^ LCG_A) & MASK48;
            long s1 = (LCG_A * s0 + LCG_B) & MASK48;
            long s2 = (LCG_A * s1 + LCG_B) & MASK48;
            if ((int)(s1 >> 17) % OFFSET != run.ox[fi]) return false;
            if ((int)(s2 >> 17) % OFFSET != run.oz[fi]) return false;
            long s3 = (LCG_A * s2 + LCG_B) & MASK48;
            if ((int)(s3 >> 17) % 5 >= 2) return false;
        }
        return true;
    }

    static List<int[]> getRingBlocks(int ring) {
        List<int[]> result = new ArrayList<>();
        if (ring == 0) { result.add(new int[]{-1,-1}); return result; }
        for (int rx = -ring-1; rx <= ring-1; rx++) result.add(new int[]{rx,  ring-1});
        for (int rx = -ring-1; rx <= ring-1; rx++) result.add(new int[]{rx, -ring-1});
        for (int rz = -ring;   rz <= ring-2; rz++) result.add(new int[]{ ring-1, rz});
        for (int rz = -ring;   rz <= ring-2; rz++) result.add(new int[]{-ring-1, rz});
        return result;
    }

    static long regionSalt(int rx, int rz) {
        return ((long)rx * RegionSeed.A + (long)rz * RegionSeed.B + STRUCT_SALT) & MASK48;
    }
}
