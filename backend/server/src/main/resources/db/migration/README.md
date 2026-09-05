# Migration compatibility notes

V11 为 Phase 5 RAG provenance 的追加迁移。它不修改旧 no-RAG 表的必填约束；历史 no-RAG run 可以没有对应的 RAG projection。V11 writer 发布前先应用迁移，reader 必须把 projection 缺失解释为“无 RAG provenance”，而不是猜测版本。

V11 的 prompt、run、step 和 model invocation provenance 行只前插入、禁止更新。回滚采用只前滚策略：停止新 writer，并将应用 pointer 切回仍被保留的 prompt/index/profile/route 版本；不要删除 V11 行，也不要执行 down migration。兼容窗口结束后再由后续迁移显式收敛保留策略。

数据库不保存 raw prompt、document、evidence text、tool schema、model request 或 output。`*_hash` 是 SHA-256 小写十六进制摘要，`*_opaque_ref` 只允许不含空白/控制字符的外部 opaque address；敏感正文必须由受控外部存储和后续鉴权读取。
