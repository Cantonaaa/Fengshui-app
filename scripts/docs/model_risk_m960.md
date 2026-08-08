# 模型风险记录（17 类 l@960）

相邻类文本嵌入 cosine ≥ 0.8 的类对，判别依赖视觉特征与 margin 过滤（模糊→未识别）；
书柜(bookshelf)已启用「书」双验证：邻近 2m 内检测到 book 才映射 study，否则丢弃。

- **wardrobe ↔ bookshelf**：cosine 0.902（文本相邻，需真机/实图验证区分；书柜类已由「书」双验证兜底）
- **safe ↔ bookshelf**：cosine 0.894（文本相邻，需真机/实图验证区分；书柜类已由「书」双验证兜底）
- **book ↔ bookshelf**：cosine 0.931（文本相邻，需真机/实图验证区分；书柜类已由「书」双验证兜底）
- **water ↔ refrigerator**：cosine 0.840（文本相邻，需真机/实图验证区分；书柜类已由「书」双验证兜底）
- **door ↔ window**：cosine 0.863（文本相邻，需真机/实图验证区分；书柜类已由「书」双验证兜底）
- **desk ↔ front desk**：cosine 0.911（文本相邻，需真机/实图验证区分；书柜类已由「书」双验证兜底）
