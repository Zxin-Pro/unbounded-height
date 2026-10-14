package dev.zxin.unboundedheight.mixin;

import dev.zxin.unboundedheight.core.HeightManager;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 维度登记 —— 「维度兼容层」的入口。
 *
 * <p>每个 {@code ServerLevel} 构造完成时，把
 * {@code DimensionType 实例 → 维度 ID} 记进 {@link HeightManager} 的身份映射。
 *
 * <p>为什么用这个钩子而不是注册表遍历：
 * <ol>
 *   <li><b>零 API 风险</b>：{@code Level.dimension()} / {@code Level.dimensionType()}
 *       从 1.20.1 到 26.3 名称未变；而 {@code RegistryAccess} 的
 *       {@code registryOrThrow} 在 26.x 被改名成 {@code lookupOrThrow}，
 *       {@code Level.dimensionTypeId()} 在 26.x 也被移除。</li>
 *   <li><b>天然覆盖动态维度</b>：数据包 / Forge 维度注册事件 / Fabric 维度加载事件
 *       添加的新维度，只要真的能进（就会创建 ServerLevel），就一定会被登记，
 *       不需要分别适配三套注册事件。</li>
 *   <li><b>天然区分客户端</b>：{@code ServerLevel} 只在逻辑服务端存在，
 *       因此专用客户端的 {@code ClientLevel} 永远不会被登记 →
 *       客户端始终使用「服务端同步过来的高度」，不会用本地配置二次覆盖造成不一致。</li>
 * </ol>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void uh$registerDimension(CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        try {
            HeightManager.registerLevel(self.dimension(), self.dimensionType());
        } catch (Throwable t) {
            dev.zxin.unboundedheight.Bridge.warn("登记维度失败（已忽略）: " + t);
        }
    }
}
