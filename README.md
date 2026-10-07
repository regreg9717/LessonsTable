# 轻课表 (LightCourse)

一款简洁美观的安卓课表应用：导入 Excel 课表文件，自动生成漂亮的周视图课表。

## 功能

- 📥 导入 `.xlsx` 和老式 `.xls` Excel 文件（扩展名不可靠时会自动识别真实格式）
- 📅 周视图课表：节次 × 星期 网格，课程按名称自动配色
- 🔆 今天一列在表头高亮，副标题显示日期
- 👆 点击课程卡片查看教室、教师、时间详情
- ⏰ 自定义每节课的上下课时间（自动保存）
- 📤 在 App 菜单里可导出符合格式的模板文件并分享

## 课表 Excel 格式（两种都支持）

### ① 教务系统导出的列表格式（优先识别，推荐）

和「我的课表.xls」一样的布局：表头包含 **课程名、上课周次、上课星期、开始节次、结束节次、上课教师、教室名称** 等列，每行是一门课的一次排课：

| 课程名 | 上课周次 | 上课星期 | 开始节次 | 结束节次 | 上课教师 | 教室名称 |
|--------|---------|---------|---------|---------|---------|---------|
| 离散数学 | 1-15周 | 星期二 | 1 | 2 | 冯琪 | 东8 |
| 计算机组成原理 | 1-15周(单) | 星期四 | 5 | 6 | 刘卫光 | 基础实验楼610 |

- 自动处理**连堂**（开始~结束节次跨多格）与**单双周**（“1-15周(单)”等，卡片上会显示“单周/双周”标记）
- 其余列（课程号、学分、课程性质等）会被忽略；缺少周次/教师/教室列也能导入

### ② 周视图网格格式（手动制表用）

| A 列 | 周一 | 周二 | 周三 | 周四 | 周五 |
|------|------|------|------|------|------|
| 第1节 | 高等数学@A栋301@王老师 | 大学英语@B栋201 | | | 体育@操场 |
| 第2节 | ... | | | | |

- 单元格内容：`课程名@教室@教师`，教室和教师可以省略

`template/课表模板.xlsx` 是格式①的示例；App 菜单里也能导出模板。`testdata/我的课表.xls` 是真实教务导出样例，`app/src/test` 中的单元测试用它验证解析。

## 构建

需要 JDK 17+ 和 Android SDK（本机配置在 `local.properties`，指向 `D:/android/Sdk`）。

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

## 工程结构

```
app/src/main/java/com/lightcourse/app/
├── MainActivity.kt      # 界面与交互（纯代码构建 UI，无 XML 布局）
├── Timetable.kt         # 数据模型 + 本地存储（SharedPreferences + JSON）
├── ExcelParser.kt       # 解析入口（分发/降级）+ 课表结构识别
├── XlsxParser.kt        # 手写 .xlsx 解析（zip + XmlPullParser，无第三方依赖）
├── TemplateExporter.kt  # 手写生成模板 .xlsx 并分享
├── CourseColors.kt      # 课程配色
scripts/make_template.js # 生成示例模板的 Node 脚本
template/课表模板.xlsx    # 示例课表
keystore/lightcourse.jks # 签名密钥（密码 lightcourse123，别名 lightcourse）
```

## 技术说明

- `.xlsx`：OpenXML（zip + XML），用内置 `ZipInputStream` + `XmlPullParser` 手写解析，避免引入庞大的 poi-ooxml
- `.xls`：老式 BIFF8 二进制格式，使用 Apache POI（仅 HSSF 部分）读取
- 签名密钥仅用于本地开发分发；正式上架请更换为自己的密钥并妥善保管
