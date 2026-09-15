# 频道数据分支（`channel`）

这个分支**只放频道清单与公钥**，不含产品代码。它存在的理由见
`.github/workflows/channel.yml` 顶部注释，一句话：

> 清单里的 `sha256_raw` / `size_raw` 必须由**真解压层镜像**算出，而层镜像 244 MB + 需要
> arm64/真 chroot → **清单只能在有层文件的那台机器上生成**。于是本机出清单、
> 本分支让 CI 用 Secret 里的私钥**签名**，两边各干自己擅长的。

## 文件

| 文件 | 谁产出 | 说明 |
|---|---|---|
| `channel.json` | **你的本机**：`sunsetlinux-channel publish-channel --base-url …` | 清单；里面的层 `url` 指向 CDN/对象存储 |
| `channel.pub` | 你的本机：`sunsetlinux-channel keygen` | 公钥，**不是秘密**；用户拿它与指纹核对 |
| `channel.json.sig` | **CI 自动生成**（不要手工放） | 对 `channel.json` 原始字节的 Ed25519 签名 |

## 怎么发

```bash
# 1) 本机（有层文件的那台）生成清单 —— 这一步不需要私钥
tools/channel/sunsetlinux-channel publish-channel \
    --base-url https://<CDN 前缀>/sunsetlinux \
    --key ~/.sunsetlinux-keys/channel.key \
    --pub ~/.sunsetlinux-keys/channel.pub \
    --name "SunsetLinux 官方" --dsh-dist-tag next
#    → 打印出要上传的文件；把层文件传到 CDN

# 2) 把清单与公钥提交到这个分支
git checkout channel
cp <发布目录>/channel.json channel/channel.json
cp ~/.sunsetlinux-keys/channel.pub channel/channel.pub
git add channel/channel.json channel/channel.pub && git commit -m "channel: 更新清单" && git push

# 3) CI 会自动：检查层 URL 可下载 → 用 Secret 签名 → 独立验签 → 提交 .sig → 发到 gh-pages
#    清单地址：https://<owner>.github.io/<repo>/channel/channel.json

# 4) 把「URL + 公钥 + 指纹」发给用户（走另一个渠道，别跟清单放同一个地方）
```

## 注意

- **`channel.json` 上传/提交后一个字节都不要再改** —— 签的是原始字节，格式化一下签名就失效；
  改了就要重新走一遍 CI 签名（推上去自动触发）。
- 层文件**不要**提交到 git（一次 244 MB，git 历史会永久保留）。放对象存储/CDN。
- 私钥只在 GitHub Secret（`CHANNEL_SIGNING_KEY`）与你的离线备份里；仓库里**绝不放**。
