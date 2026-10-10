# Next AI Draw.io MCP 使用说明

## 1. 目的

本文档总结本项目中如何基于 `.vscode/mcp.json` 中配置的 `drawio` MCP 服务，使用 Next AI Draw.io 生成可编辑架构图，并导出到论文使用的图片文件。

本次实际产物包括：

- [fig4-2-inference-compat.drawio](../paper/fig4-2-inference-compat.drawio)
- [fig4-2-inference-compat.png](../paper/fig4-2-inference-compat.png)
- [paper-new.docx](../paper/paper-new.docx)

本文保留历史绘图流程；当时使用的 SVG 与独立 mxGraph XML 未保存在当前仓库，现有产物以上述文件为准，不作为当前应用架构或运行验收证据。

## 2. MCP 配置

仓库中的 MCP 配置文件是 [mcp.json](../../.vscode/mcp.json)：

```json
{
  "mcpServers": {
    "drawio": {
      "command": "npx",
      "args": ["@next-ai-drawio/mcp-server@latest"]
    }
  }
}
```

这表示：

- MCP 服务名为 `drawio`
- 启动方式是 `npx @next-ai-drawio/mcp-server@latest`
- 服务通过 `stdio` 与客户端通信

## 3. 可用工具

该 MCP 服务启动后，核心工具如下：

1. `start_session`
   作用：启动一个绘图会话，并打开浏览器预览页。
2. `create_new_diagram`
   作用：传入完整的 `mxGraphModel XML`，创建或整体替换当前图。
3. `get_diagram`
   作用：获取当前图的 XML。
4. `edit_diagram`
   作用：基于已有图按 `add/update/delete` 方式修改节点。
5. `export_diagram`
   作用：导出 `.drawio`、`.png`、`.svg`。

## 4. 推荐工作流

### 4.1 新建图

适合“从零绘制一张完整架构图”的场景。

流程如下：

1. 读取 `.vscode/mcp.json` 中的 `drawio` 配置。
2. 启动 MCP 客户端，并连接 `drawio` 服务。
3. 调用 `start_session`。
4. 生成完整的 `mxGraphModel XML`。
5. 调用 `create_new_diagram`，把 XML 整体写入当前图。
6. 调用 `export_diagram`，导出 `.drawio` 或图片。

### 4.2 编辑已有图

适合“在现有图上微调”的场景。

流程如下：

1. 调用 `get_diagram` 获取当前 XML。
2. 解析现有节点 `id`。
3. 调用 `edit_diagram` 执行 `add/update/delete`。
4. 调用 `export_diagram` 导出。

注意：

- `edit_diagram` 之前必须先 `get_diagram`
- `create_new_diagram` 会整体替换当前图，不适合小修小改

## 5. 本项目中的实际做法

针对论文 `4.1.2 推理模块设计` 中的“支持多种轻量化目标检测模型及其运行时环境的灵活接入”架构图，本次采用了以下方式：

1. 先根据项目真实实现整理图结构。
   结合项目代码，图中体现了：
   `ONNX Runtime`、`Paddle Lite`、统一推理接口、模型适配层、运行时接入层、以及端侧执行流水线。
2. 生成完整的 `mxGraphModel XML`。
3. 通过 MCP 调用 `start_session + create_new_diagram + export_diagram(format=drawio)` 导出可编辑源图。
4. 同步生成论文使用的 `PNG/SVG` 文件。
5. 将 PNG 回写到 [paper-new.docx](../paper/paper-new.docx) 中 `图4-2` 对应的内嵌图片资源。

## 6. 可复用脚本示例

下面的示例展示如何读取 [mcp.json](../../.vscode/mcp.json)，连接 `drawio` MCP 服务，并导出 `.drawio` 文件。从仓根运行，事先准备示例所需的独立 mxGraph XML；该 XML 不在当前仓库中。

```js
import fs from "node:fs/promises";
import path from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const root = process.cwd();
const configPath = path.join(root, ".vscode", "mcp.json");
const xmlPath = path.join(root, "docs", "paper", "fig4-2-inference-compat.mxgraph.xml");
const outPath = path.join(root, "docs", "paper", "fig4-2-inference-compat.drawio");

const config = JSON.parse(await fs.readFile(configPath, "utf8"));
const drawio = config.mcpServers.drawio;

const transport = new StdioClientTransport({
  command: drawio.command,
  args: drawio.args ?? [],
  env: drawio.env,
  stderr: "pipe",
});

const client = new Client(
  { name: "doctor-cam-figure-export", version: "1.0.0" },
  { capabilities: {} }
);

try {
  await client.connect(transport);

  const xml = await fs.readFile(xmlPath, "utf8");

  await client.callTool({
    name: "start_session",
    arguments: {},
  });

  await client.callTool({
    name: "create_new_diagram",
    arguments: { xml },
  });

  await client.callTool({
    name: "export_diagram",
    arguments: {
      path: outPath,
      format: "drawio",
    },
  });
} finally {
  await client.close();
}
```

运行前需要：

- 已安装 Node.js
- 可以联网安装 `@modelcontextprotocol/sdk`
- 本机可打开浏览器

## 7. XML 结构要求

`create_new_diagram` 需要传入完整的 `mxGraphModel XML`，最小结构如下：

```xml
<mxGraphModel>
  <root>
    <mxCell id="0"/>
    <mxCell id="1" parent="0"/>
    <mxCell id="2" value="Shape" vertex="1" parent="1">
      <mxGeometry x="100" y="100" width="120" height="60" as="geometry"/>
    </mxCell>
  </root>
</mxGraphModel>
```

实践建议：

- 顶层图元统一挂在 `parent="1"` 下
- 所有节点都使用稳定、可读的 `id`
- 文本、形状、连线都一次性生成，避免后续频繁修补
- 若需要可维护性，保留一份独立的 `mxgraph.xml` 源文件

## 8. 导出行为说明

这是本次使用中最重要的经验点。

### 8.1 `.drawio` 导出

`.drawio` 导出本质上是把当前 XML 保存为文件，最稳定，适合作为“可编辑源图”。

### 8.2 `.png` / `.svg` 导出

`PNG/SVG` 导出依赖浏览器中的 draw.io 页面配合完成，因此有以下前提：

- 必须先 `start_session`
- 浏览器页要保持打开
- 页面要已经连接到对应的 `?mcp=...` 会话

如果浏览器未打开或未同步，`export_diagram` 可能超时。

## 9. 本次实践中的限制与处理

本次实践中验证到以下事实：

1. `drawio` MCP 服务本身适合生成和导出 `.drawio` 源图。
2. `PNG/SVG` 导出依赖浏览器态，不适合在完全无界面的批处理中当作唯一方案。
3. 为保证论文插图结果稳定，本次最终采用了“`MCP 导出 drawio 源图 + 本地稳定生成 PNG/SVG + 回写 docx`”的组合方式。

因此，如果目标是论文或报告中的最终插图，建议采用：

1. 用 MCP 生成 `.drawio`
2. 保留一份可审阅的 `mxgraph.xml`
3. 生成最终发布图片
4. 再写入 Word 文档或其他排版文件

## 10. 与本项目文件的对应关系

本次相关文件如下：

- MCP 配置：
  [mcp.json](../../.vscode/mcp.json)
- draw.io 可编辑源图：
  [fig4-2-inference-compat.drawio](../paper/fig4-2-inference-compat.drawio)
- mxGraph 原始结构：
  `fig4-2-inference-compat.mxgraph.xml`（未入当前仓库）
- 论文图片：
  [fig4-2-inference-compat.png](../paper/fig4-2-inference-compat.png)
  `fig4-2-inference-compat.svg`（未入当前仓库）
- 已写回图片的论文文件：
  [paper-new.docx](../paper/paper-new.docx)

## 11. 结论

在本项目中，基于 Next AI Draw.io 的 MCP 接入，最适合承担两件事：

1. 用自然语言或程序化 XML 快速生成可编辑架构图
2. 将图结构稳定导出为 `.drawio` 供后续继续修改

而对于论文最终交付图片，推荐把 MCP 作为“可编辑源图生成器”，再配合本地稳定图片产出和文档回写流程，整体可控性更高。
