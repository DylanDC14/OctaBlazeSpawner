package main;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.rand.seed.RegionSeed;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.version.MCVersion;
import fortressGen.FortressGenerator;
import fortressGen.FortressGenerator.PieceInfo;

import java.util.ArrayList;
import java.util.List;

public class MITMQuadFortressOctaSpawner {

    static final MCVersion VERSION     = MCVersion.v1_16_1;
    static final int       STRUCT_SALT = 30084232;
    static final int       SPACING     = 27;
    static final int       OFFSET      = 23;

    static final long LCG_A  = 0x5deece66dL;
    static final long LCG_B  = 0xbL;
    static final long MASK48 = (1L << 48) - 1;

    static final int START_RADIUS  = 20000;
    static final int SEARCH_RADIUS = 330000;
    static final int MAX_DIST      = 32;

    public static void main(String[] args) {
        StructureMITM.verbose = false;

        List<StructureMITM.Constraint> originConstraints = buildConstraints(-1, -1);
        StructureMITM mitm = new StructureMITM(originConstraints);
        List<Long> candidates = mitm.findSeeds();

        long[] seedArr = candidates.stream()
            .filter(s -> verifyDirect(s, -1, -1))
            .distinct().sorted()
            .mapToLong(Long::longValue).toArray();

        for (int ring = START_RADIUS; ring < SEARCH_RADIUS; ring++) {
            final int r = ring;
            getRingBlocks(ring).parallelStream().forEach(block -> {
                int rx = block[0], rz = block[1];
                long delta = (-(long)(rx + 1) * RegionSeed.A - (long)(rz + 1) * RegionSeed.B) & MASK48;
                
                int[] cxArr = {
                    rx      * SPACING + (OFFSET - 1),
                    (rx+1)  * SPACING,
                    rx      * SPACING + (OFFSET - 1),
                    (rx+1)  * SPACING
                };
                int[] czArr = {
                    rz      * SPACING + (OFFSET - 1),
                    rz      * SPACING + (OFFSET - 1),
                    (rz+1)  * SPACING,
                    (rz+1)  * SPACING
                };

                ChunkRand rand    = new ChunkRand();
                FortressGenerator fortGen = new FortressGenerator(VERSION);
                for (long originSeed : seedArr) {
                    long seed = (originSeed + delta) & MASK48;
                    checkSeed(seed, rx, rz, cxArr, czArr, rand, fortGen);
                }
            });
            System.out.printf("done ring %d%n", r);
        }
    }

    private static void checkSeed(long structureSeed, int rx, int rz, int[] cxArr, int[] czArr, ChunkRand rand, FortressGenerator fortGen) {
        
        ArrayList<BPos> xzSpawners = new ArrayList<>(8);

        for (int fi = 0; fi < 4; fi++) {
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
        
        ArrayList<BPos> spawners = new ArrayList<>(8);

        for (int fi = 0; fi < 4; fi++) {
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

        System.out.println(structureSeed + " " + rx + " " + rz);
        System.out.printf("/tp @p %d 70 %d%n", (rx + 1) * SPACING * 16, (rz + 1) * SPACING * 16);
        System.out.printf("8 spawners all within %d blocks:%n", MAX_DIST);
        for (BPos s : spawners) {
            System.out.printf("  /tp @p %d %d %d%n", s.getX(), s.getY(), s.getZ());
        }
        System.out.println();
    }
    
    static List<int[]> getRingBlocks(int ring) {
        List<int[]> result = new ArrayList<>();
        if (ring == 0) {
            result.add(new int[]{-1, -1});
            return result;
        }
        for (int rx = -ring - 1; rx <= ring - 1; rx++)
            result.add(new int[]{rx, ring - 1});
        for (int rx = -ring - 1; rx <= ring - 1; rx++)
            result.add(new int[]{rx, -ring - 1});
        for (int rz = -ring; rz <= ring - 2; rz++)
            result.add(new int[]{ring - 1, rz});
        for (int rz = -ring; rz <= ring - 2; rz++)
            result.add(new int[]{-ring - 1, rz});
        return result;
    }

    static List<StructureMITM.Constraint> buildConstraints(int rx, int rz) {
        return List.of(
            new StructureMITM.Constraint(regionSalt(rx,   rz  ), OFFSET - 1, OFFSET - 1, OFFSET),
            new StructureMITM.Constraint(regionSalt(rx+1, rz  ), 0,          OFFSET - 1, OFFSET),
            new StructureMITM.Constraint(regionSalt(rx,   rz+1), OFFSET - 1, 0,          OFFSET),
            new StructureMITM.Constraint(regionSalt(rx+1, rz+1), 0,          0,          OFFSET)
        );
    }

    static long regionSalt(int rx, int rz) {
        return ((long) rx * RegionSeed.A + (long) rz * RegionSeed.B + STRUCT_SALT) & MASK48;
    }

    static boolean verifyDirect(long seed, int rx, int rz) {
        int[]   tX  = { OFFSET - 1, 0,          OFFSET - 1, 0 };
        int[]   tZ  = { OFFSET - 1, OFFSET - 1, 0,          0 };
        int[][] reg = { {rx, rz}, {rx+1, rz}, {rx, rz+1}, {rx+1, rz+1} };
        for (int i = 0; i < 4; i++) {
            long salt = regionSalt(reg[i][0], reg[i][1]);
            long s0 = ((seed + salt) ^ LCG_A) & MASK48;
            long s1 = (LCG_A * s0 + LCG_B) & MASK48;
            long s2 = (LCG_A * s1 + LCG_B) & MASK48;
            if ((int)(s1 >> 17) % OFFSET != tX[i]) return false;
            if ((int)(s2 >> 17) % OFFSET != tZ[i]) return false;
            long s3 = (LCG_A * s2 + LCG_B) & MASK48;
            if ((int)(s3 >> 17) % 5 >= 2) return false;
        }
        return true;
    }
}
