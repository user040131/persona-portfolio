dependencies {
    api(project(":common"))
    api(project(":quant-client"))
    implementation(project(":profile"))
    implementation(libs.spring.boot.starter.data.jpa)
    api(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
}
