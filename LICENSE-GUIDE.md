# 授权管理操作手册

## 概述

每个客户购买后，你需要为其**生成一个授权码**，客户在应用中输入授权码完成激活。激活后授权码会绑定该客户的机器，其他人拿到同一授权码无法使用。

---

## 一、生成授权码（卖给客户前操作）

### 请求

```
POST http://8.152.161.203:18080/api/license/generate
Content-Type: application/json

{
  "customer": "客户名称",
  "days": 365
}
```

### 参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| customer | String | 是 | 客户标识，填什么都行：姓名、微信昵称、手机号、公司名等，方便你日后查阅 |
| days | Integer | 否 | 有效天数。不传或传 null 表示永久有效 |

### 示例

**永久授权：**
```json
{ "customer": "张三" }
```

**一年授权：**
```json
{ "customer": "李四公司", "days": 365 }
```

**半年授权：**
```json
{ "customer": "王五-微信wx123", "days": 180 }
```

### 返回示例

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "id": 1,
    "licenseKey": "A3KN-P7HM-X2WR-BF6Q",
    "customer": "张三",
    "status": 0,
    "expireAt": null,
    "machineId": null,
    "activatedAt": null,
    "token": null,
    "createTime": "2026-06-02T15:00:00"
  }
}
```

> **把 `licenseKey` 发给客户即可**，其他字段是你自己记录用的。

---

## 二、查看所有授权

### 请求

```
GET http://8.152.161.203:18080/api/license/list
```

### 返回示例

```json
{
  "code": 200,
  "data": [
    {
      "id": 1,
      "licenseKey": "A3KN-P7HM-X2WR-BF6Q",
      "customer": "张三",
      "status": 1,
      "machineId": "a1b2c3...",
      "expireAt": null,
      "activatedAt": "2026-06-02T15:30:00",
      "token": "d4e5f6...",
      "createTime": "2026-06-02T15:00:00"
    }
  ]
}
```

### 状态说明

| status | 含义 |
|--------|------|
| 0 | 未激活（授权码已生成，客户还没输入） |
| 1 | 已激活（客户已使用，绑定机器） |
| 2 | 已禁用（被你手动禁用，客户无法继续使用） |

---

## 三、禁用授权（客户违规时）

禁用后，该客户下次启动应用时所有 API 请求将被拒绝，无法使用。

### 请求

```
POST http://8.152.161.203:18080/api/license/disable/{id}
```

> `{id}` 是授权记录的 id，从列表接口获取。

### 示例

```
POST http://8.152.161.203:18080/api/license/disable/1
```

---

## 四、解绑机器（客户换电脑时）

客户换新电脑后，旧授权码仍显示"已绑定其他设备"。你执行解绑后，客户可在新电脑上重新输入同一授权码激活。

### 请求

```
POST http://8.152.161.203:18080/api/license/unbind/{id}
```

### 示例

```
POST http://8.152.161.203:18080/api/license/unbind/1
```

---

## 五、快速操作指南（用 Postman）

1. 打开 Postman，新建请求
2. 方法选 `POST`，URL 填 `http://8.152.161.203:18080/api/license/generate`
3. Headers 添加 `Content-Type: application/json`
4. Body 选 `raw` → `JSON`，填入参数
5. 点 Send，从返回结果中复制 `licenseKey` 发给客户

> 授权管理接口（generate/list/disable/unbind）不受授权拦截器限制，无需带 token。

---

## 六、常见场景

| 场景 | 操作 |
|------|------|
| 新客户购买 | 调用生成授权码 → 把授权码发给客户 |
| 客户说"授权码已绑定其他设备" | 调用解绑 → 让客户重新输入授权码 |
| 客户要求退款/违规使用 | 调用禁用 → 客户立即无法使用 |
| 客户授权到期 | 重新生成一个新授权码发给客户 |
| 想看某个授权码的状态 | 调用列表接口，按 customer 或 licenseKey 查找 |

---

## 七、授权码格式

格式为 `XXXX-XXXX-XXXX-XXXX`，由大写字母和数字随机生成，排除易混淆的 I/O/0/1。
