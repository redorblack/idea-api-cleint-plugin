plugins {
    kotlin("jvm")
}

dependencies {
    // core 不依赖 IntelliJ API，可独立编译/测试
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
