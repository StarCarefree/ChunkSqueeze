# ChunkSqueeze

**无损压缩 Minecraft 地形区块，减少世界存档占用。**

ChunkSqueeze 面向模组整合包和长期运行的世界，通过紧凑区域存储、区域共享方块状态字典和完整区段去重，减少重复数据与磁盘分配浪费。保留方块位置、完整属性、光照、生物群系及其他模组的 NBT 数据，不依赖运行时方块注册表编号。

## 主要功能

- **紧凑区域存储**：使用 512 字节对齐的 `.cqs` 文件，减少原版 MCA 4 KiB 分配单元产生的空间浪费。
- **区域共享状态字典**：同一区域内共享完整方块状态与属性，减少跨区块重复存储。
- **完整区段精确去重**：仅合并整个区段 NBT 完全相同的数据，保留区段高度、光照、生物群系和未知字段。
- **后台重编码**：先将完整区块数据持久化，再由有限后台线程进行空间优化，缩短前台保存等待。后台任务不会覆盖后来的保存或删除。
- **并发迁移旧世界**：通过管理指令转换已有 MCA 区域，支持进度查询和安全取消；迁移期间禁止玩家进入。
- **完整性检查与故障保护**：校验载荷、索引和文件身份，使用写时复制与镜像提交。遇到存储故障时拒绝继续写入或回退为空地形。
- **离线转换工具**：附带 Python 工具，可将世界转换为紧凑存储，或还原为标准 MCA。

模组处理地形区块存储，实体和 POI 区域保持原格式。实际空间收益取决于世界内容；只有少量区块的稀疏区域可能因固定索引开销而变大。

## 管理指令

| 指令 | 功能 | 权限等级 |
|---|---|---:|
| `/chunksqueeze status` | 查看存储统计、后台任务与故障锁状态 | 2 |
| `/chunksqueeze migrate` | 无人在线时开始迁移 | 4 |
| `/chunksqueeze migrate force` | 断开在线玩家并开始迁移维护 | 4 |
| `/chunksqueeze migrate status` | 查看迁移进度 | 4 |
| `/chunksqueeze migrate cancel` | 请求安全取消，保留已完成的迁移 | 4 |

迁移只处理当前服务器已加载维度的地形 MCA，不批量重编码已有 CQS。迁移前保存世界，每个区域校验并提交成功后，**删除该区域的原 MCA 与相关 MCC，不保留自动备份**；请自行备份。无净空间收益的区域会跳过。

单人游戏中，`force` 断开本地玩家可能导致整合服务器关闭，建议使用离线工具转换单人存档。

## 配置

配置文件：`config/chunksqueeze-common.toml`。

```toml
# Minimum compressed bytes saved per chunk.
minimumSavingBytes = 128
# Only encode chunks that save an allocation unit.
requireSectorSaving = true
# Use compact files for new regions.
compactRegions = true
# Migration workers. 0 = auto; bounded by CPU and heap.
migrationThreads = 0
# Background workers. 0 = auto; up to 2.
backgroundThreads = 0
```

默认配置即可使用。迁移线程自动模式最多 8 个，后台线程最多 2 个，均受 CPU 和最大堆限制。后台线程配置在重启后生效。关闭 `compactRegions` 只影响新区域的创建，不会让已有 CQS 切回 MCA。

保存返回时，完整区块数据已经落盘，但不代表后台空间优化已完成。队列饱和、区域关闭或停服时，尚未优化的数据会保持普通 NBT，之后可在区域重开时重新尝试。文件内部碎片按后续 flush 的门槛整理，不保证立即达到最紧凑体积。

## 离线转换与卸载

停止游戏或服务端，使用 Python 3.10+ 执行工具，不需要第三方依赖。目标目录必须不存在，源目录保持不变。

将标准 MCA 世界转换为 CQS：

```bash
python -I -X utf8 tools/compact_world.py "D:/原世界" "D:/紧凑世界"
```

还原为标准 MCA：

```bash
python -I -X utf8 tools/restore_world.py "D:/紧凑世界" "D:/标准世界"
```

**卸载模组、降级到不支持当前格式的版本，或使用只识别 MCA 的地图编辑器前，必须先导出标准世界。** 离线转换只改变存储布局，状态共享和区段去重会在后续保存优化时逐步采用。

运行期间不要用外部程序修改世界文件。遇到存储故障时应停服排查，不要删除 owner 或迁移日志来绕过保护。

## 验证与性能

- [后台重编码与固定样本测试](doc/BACKGROUND_REENCODING_0.6.0.md)
- [ATM10 正式服务端对照](doc/ATM_BACKGROUND_0.6.0.md)
- [NBT 编码规范](doc/FORMAT.md) · [CQS 区域格式](doc/COMPACT_FORMAT.md)

## 许可证

本项目采用 [Mozilla Public License 2.0（MPL-2.0）](LICENSE)。
