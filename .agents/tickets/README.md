# Worker Tickets

这里仅存放 AI Worker 的机器可读执行合同，不是产品说明文档。

- 一张任务卡对应一个 YAML 文件，包含 ownership、read-only 范围、验收条件、必跑命令和回报格式。
- Worker 只读取自己被分派的 Ticket；任务目标看 `backlog/tasks`。
- 实际修改文件、测试结果、风险和回滚方式以 Git commit body 为准。
- `TICKET_TEMPLATE.yaml` 只用于新建 Ticket，不代表当前任务状态。
