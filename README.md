# 剪辑转换（iOS 侧载用）

在 iPhone 上截取视频、把视频转成音频的小工具，全部在手机上处理，不联网。

## 功能
- **截取视频**：直接输入开始 / 结束时间（`83.5`、`1:23`、`1:02:03.5`、`1分23秒` 都行），
  也可以边播放边点 ⌖ 把当前位置填进去；可以「试播这一段」确认
  - 快速模式：不重新编码，几秒完成、画质无损
  - 精确剪切：重新编码，切点精确到帧
- **转成音频**：AAC（.m4a）、AAC（.aac）、MP3、OGG（Vorbis），码率 96～320 kbps 可选；
  同样可以只转选中的那一段
- 文件来源：相册，或「文件」App（也能选 yt-d 下载的视频：我的 iPhone → 视频下载）
- 在「文件」App 里对视频/音频点「分享」→ 选「剪辑转换」也能直接打开
- 结果可以分享、存到相册，也会出现在「文件」App →「我的 iPhone」→「剪辑转换」

## 编译 IPA（不需要 Mac）
1. 把这个仓库的全部内容（包括 `.github` 文件夹）放到 GitHub 上。
2. 打开仓库的 **Actions** 页面，选 **Build IPA** → **Run workflow**（推送到 main 也会自动编译）。
3. 大约 5～10 分钟后，点进完成的那次运行，在页面底部 **Artifacts** 下载 `VideoTool-ipa`，解压得到 `VideoTool.ipa`。
4. 用平时的侧载工具（Sideloadly / AltStore / SideStore / TrollStore 等）安装。

## 用到的开源库
- MP3 编码：[LAME](https://lame.sourceforge.io/)（LGPL），源码在 `Vendor/lame`
- OGG 编码：[libogg / libvorbis](https://xiph.org/)（BSD），源码在 `Vendor/ogg`、`Vendor/vorbis`
- AAC 用的是 iPhone 系统自带的编码器
- `Codecs/AudioEncoders.c` 是给 Swift 调用的简单封装

## 版本号规则
- 修 bug：+0.01（例：1.03 → 1.04）
- 加功能：+0.1（例：1.03 → 1.13）
- 版本号在 `project.yml` 的 `CFBundleShortVersionString`，`CFBundleVersion` 每次 +1
- App 首页底部会显示当前版本

## 更新记录
- 1.0：初始版本（截取视频、转 AAC / MP3 / OGG）
