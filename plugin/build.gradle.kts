plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))

    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion")
        )
        // Java PSI 支持（扫描 @RestController / @FeignClient 需要）
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        // 二进制兼容性校验工具（verifyPlugin 任务需要）
        pluginVerifier()
        // Marketplace ZIP 签名器（signPlugin 任务需要，缺它报 No ZIP Signer executable）
        zipSigner()
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // LightJavaCodeInsightFixtureTestCase 在 Java 插件的测试框架里，Platform 不含
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
    }

    // PSI 测试基类是 JUnit3 风格（UsefulTestCase），需要 JUnit4 运行器
    testImplementation("junit:junit:4.13.2")
    // UsefulTestCase 的断言走 opentest4j，平台测试框架不带这个传递依赖
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    // 产物名与插件安装目录名用 ApiScope，而不是模块名 plugin
    projectName = providers.gradleProperty("pluginName").get()

    // 对着本机实际安装的 IDE 验二进制兼容性（不用下载 IDE）
    pluginVerification {
        ides {
            providers.gradleProperty("verifyAgainstLocalIde").orNull
                ?.takeIf { it.isNotBlank() }
                ?.let { local(file(it)) }
        }
    }

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = "242"
            // 不锁上限，避免每次 IDEA 升级插件失效
            untilBuild = provider { null }
        }
    }

    // 插件签名（Marketplace 自 2021 起强制签名）。三项全走环境变量，绝不硬编码。
    // 证书链 / 私钥生成步骤见 docs/PUBLISHING.md；本地不设这些变量时 signPlugin 自动跳过。
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // 发布到 JetBrains Marketplace。token 从 Marketplace → My Tokens 生成，走环境变量。
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // channels 留默认（stable）；想先发内测再放开时设 channels = listOf("beta")
    }
}

kotlin {
    jvmToolchain(17)
}
