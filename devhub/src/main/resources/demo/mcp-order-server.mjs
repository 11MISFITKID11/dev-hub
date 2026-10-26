#!/usr/bin/env node
/**
 * 演示用 MCP server（stdio 传输）—— 订单查询
 *
 * 零依赖 Node 实现 MCP 协议（JSON-RPC 2.0 over stdin/stdout），
 * 用于演示 HelpHub 的 MCP 客户端接入：
 *   agent.mcp.servers[0] = { key: demo-order, transport: stdio, command: [node, <本文件>] }
 *
 * 暴露两个工具：
 *   query_order(orderId)      → 模拟订单详情（状态/金额/物流）
 *   get_refund_policy(orderType) → 退款政策条款
 *
 * 生产等价物：把 command 换成任意官方/自建 MCP server
 * （filesystem、mysql、jira、web-search……），ToolRegistry 侧零改动。
 */
import readline from 'node:readline';

const ORDERS = {
  'SO-2024-1001': { status: '已发货', amount: 299.0, items: '机械键盘 x1', logistics: '顺丰 SF1234567890', eta: '2026-10-27' },
  'SO-2024-1002': { status: '待支付', amount: 1599.0, items: '显示器 x1', logistics: null, eta: null },
  'SO-2024-1003': { status: '退款中', amount: 89.0, items: '鼠标垫 x2', logistics: null, eta: null },
  'SO-2024-1004': { status: '已完成', amount: 459.0, items: '耳机 x1', logistics: '已签收', eta: '2026-10-20' },
};

const REFUND_POLICY = {
  digital: '数字商品：未激活可全额退款；已激活 48 小时内且使用不足 2 小时可退 70%。',
  physical: '实体商品：签收后 7 天无理由退货（需不影响二次销售），运费险覆盖首重运费。',
  default: '通用政策：订单完成后 7 个自然日内可申请售后，特殊商品以商品页说明为准。',
};

const TOOLS = [
  {
    name: 'query_order',
    description: '外部订单系统查询（MCP 远程）。凡用户给出具体订单号询问订单状态/物流/金额/商品时【必须】调用本工具获取真实数据，禁止凭记忆或猜测回答订单状态。',
    inputSchema: {
      type: 'object',
      properties: {
        orderId: { type: 'string', description: '订单号，如 SO-2024-1001' },
      },
      required: ['orderId'],
    },
  },
  {
    name: 'get_refund_policy',
    description: '外部售后系统（MCP 远程）：查询指定订单类型的退款/售后政策条款',
    inputSchema: {
      type: 'object',
      properties: {
        orderType: { type: 'string', description: '订单类型：digital/physical，缺省为通用' },
      },
      required: [],
    },
  },
];

function callTool(name, args) {
  args = args || {};
  if (name === 'query_order') {
    const id = String(args.orderId || '').trim().toUpperCase();
    const order = ORDERS[id];
    if (!order) {
      return { content: [{ type: 'text', text: `未找到订单 ${id}。可查询的演示订单号：${Object.keys(ORDERS).join('、')}` }], isError: true };
    }
    return {
      content: [{
        type: 'text',
        text: `订单 ${id}：状态=${order.status}，金额=¥${order.amount.toFixed(2)}，商品=${order.items}` +
              `${order.logistics ? `，物流=${order.logistics}` : ''}${order.eta ? `，预计=${order.eta}` : ''}`,
      }],
      isError: false,
    };
  }
  if (name === 'get_refund_policy') {
    const type = String(args.orderType || 'default').toLowerCase();
    return {
      content: [{ type: 'text', text: REFUND_POLICY[type] || REFUND_POLICY.default }],
      isError: false,
    };
  }
  return { content: [{ type: 'text', text: `未知工具: ${name}` }], isError: true };
}

const rl = readline.createInterface({ input: process.stdin, terminal: false });

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

rl.on('line', (line) => {
  line = line.trim();
  if (!line) return;
  let req;
  try {
    req = JSON.parse(line);
  } catch {
    return; // 非 JSON 行直接忽略（stdio 噪音）
  }
  const { id, method, params } = req;
  const isNotification = id === undefined || id === null;

  try {
    switch (method) {
      case 'initialize':
        send({
          jsonrpc: '2.0', id,
          result: {
            // 回显客户端请求的协议版本，保证兼容
            protocolVersion: params?.protocolVersion || '2025-06-18',
            capabilities: { tools: {} },
            serverInfo: { name: 'helphub-demo-order-server', version: '1.0.0' },
          },
        });
        break;
      case 'notifications/initialized':
      case 'notifications/cancelled':
        break; // 通知无响应
      case 'ping':
        send({ jsonrpc: '2.0', id, result: {} });
        break;
      case 'tools/list':
        send({ jsonrpc: '2.0', id, result: { tools: TOOLS } });
        break;
      case 'tools/call':
        send({ jsonrpc: '2.0', id, result: callTool(params?.name, params?.arguments) });
        break;
      default:
        if (!isNotification) {
          send({ jsonrpc: '2.0', id, error: { code: -32601, message: `Method not supported: ${method}` } });
        }
    }
  } catch (e) {
    if (!isNotification) {
      send({ jsonrpc: '2.0', id, error: { code: -32603, message: String(e) } });
    }
  }
});

rl.on('close', () => process.exit(0));
