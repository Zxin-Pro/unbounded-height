package dev.zxin.unboundedheight.mixin;

import dev.zxin.unboundedheight.core.HeightManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 三个加载器统一使用官方（mojmap / official）映射：
//   Forge / NeoForge: mappings channel 'official' / ModDevGradle 默认 official
//   Fabric:           mappings loom.officialMojangMappings()
// 因此 DimensionType 的包名从 1.20.1 到 26.3 完全一致，核心 Mixin 零版本分支。
import net.minecraft.world.level.dimension.DimensionType;

/**
 * ★ 本模组的核心注入点 ★
 *
 * <p>1.18+ 中「世界有多高」这件事<b>只有一个真源</b>：{@code DimensionType} 的
 * {@code minY} / {@code height} 两个字段（及其记录访问器）。
 * 我们对这两个访问器做 {@code HEAD + cancellable} 注入，让它们返回配置值。
 *
 * <p>为什么只改这里就够了（全部经官方 client.jar 字节码实测，见 docs/VERIFIED_INTERNALS.md）：
 * <ul>
 *   <li>{@code LevelHeightAccessor} 只把 {@code getMinBuildHeight()/getHeight()} 声明为抽象，
 *       {@code getSectionsCount()/getMinSection()/getMaxSection()/isOutsideBuildHeight()} 等
 *       全是建立在这两个之上的 default 方法 → 自动跟随。</li>
 *   <li>{@code Level} 与 {@code ChunkAccess} 的这两个方法都只是转发到 DimensionType。</li>
 *   <li>区块 sections 数组容量 = {@code getSectionsCount()} → 自动按新高度分配。</li>
 *   <li>{@code Heightmap} 的位宽 = {@code Mth.ceillog2(chunk.getHeight() + 1)} → 自动扩容。</li>
 *   <li>区块存档用绝对 section Y（{@code putByte("Y", sectionY)}）→ 旧方块位置不变。</li>
 *   <li><b>客户端</b>：原版注册表同步用 {@code RecordCodecBuilder} 的
 *       {@code forGetter(DimensionType::minY)} 编码<b>活对象</b>，方法引用仍指向被注入的方法
 *       → 服务端算出的高度原样发给客户端，客户端据此分配区块存储、渲染与碰撞。
 *       因此<b>不需要自定义网络包</b>也能保证客户端正确。</li>
 * </ul>
 *
 * <p><b>原生值读取：</b>首次询问时用 {@code uh$readingNative} 开关重入访问器，
 * 让原版方法体正常执行，从而拿到该维度未被改写的高度 ——
 * 这是 {@code compat.respectNativeHeight}（尊重模组维度自带高度）的判据。
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeMixin {

    @Unique
    private static final ThreadLocal<Boolean> uh$readingNative =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "minY", at = @At("HEAD"), cancellable = true)
    private void uh$minY(CallbackInfoReturnable<Integer> cir) {
        int[] eff = uh$effective();
        if (eff != null && eff.length != 0) {
            cir.setReturnValue(eff[0]);
        }
    }

    @Inject(method = "height", at = @At("HEAD"), cancellable = true)
    private void uh$height(CallbackInfoReturnable<Integer> cir) {
        int[] eff = uh$effective();
        if (eff != null && eff.length != 0) {
            cir.setReturnValue(eff[1]);
        }
    }

    @Inject(method = "logicalHeight", at = @At("HEAD"), cancellable = true)
    private void uh$logicalHeight(CallbackInfoReturnable<Integer> cir) {
        int[] eff = uh$effective();
        if (eff != null && eff.length != 0) {
            cir.setReturnValue(eff[2]);
        }
    }

    /** 快路径：绝大多数调用只是一次缓存查询。 */
    @Unique
    private int[] uh$effective() {
        if (uh$readingNative.get()) {
            return null;
        }
        int[] cached = HeightManager.cached(this);
        if (cached != null) {
            return cached;
        }
        return uh$compute();
    }

    /** 慢路径：读取原生值（重入原版方法体）后交给 HeightManager 决策。 */
    @Unique
    private int[] uh$compute() {
        DimensionType self = (DimensionType) (Object) this;
        int nativeMinY;
        int nativeHeight;
        int nativeLogical;
        uh$readingNative.set(Boolean.TRUE);
        try {
            nativeMinY = self.minY();
            nativeHeight = self.height();
            nativeLogical = self.logicalHeight();
        } finally {
            uh$readingNative.set(Boolean.FALSE);
        }
        return HeightManager.effective(self, nativeMinY, nativeHeight, nativeLogical);
    }
}
