dependencies {
    api(project(":common"))
    implementation(project(":quant-client"))
    implementation(project(":profile"))
    implementation(project(":portfolio"))
    implementation(project(":recommendation"))
    implementation(libs.spring.boot.starter.data.jpa)
    api(libs.jackson.databind)

    testImplementation(libs.spring.boot.starter.test)
}
