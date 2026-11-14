dependencies {
    api(project(":common"))
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.jackson.databind)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
}
