// FraudShield CI/CD Pipeline
// Triggers: every PR to main + every push to main
//
// 流水线阶段 (Pipeline stages):
//   Checkout → Code Quality (parallel) → Unit Tests → Integration Tests
//   → Build JAR → Build Docker Image → [main only] Deploy
//
// 本地运行: docker compose -f jenkins/docker-compose.jenkins.yml up -d
// Local run: docker compose -f jenkins/docker-compose.jenkins.yml up -d

pipeline {
    agent any

    environment {
        // Azure target — override any of these as Jenkins job/global env vars if they change
        ACR_NAME           = "${env.ACR_NAME ?: 'fraudshield5393acr'}"
        RESOURCE_GROUP     = "${env.RESOURCE_GROUP ?: 'fraudshield-rg'}"
        CONTAINER_APP_ENV  = "${env.CONTAINER_APP_ENV ?: 'fraudshield-env'}"
        DEPLOY_TARGET      = "${env.DEPLOY_TARGET ?: 'azure'}"
        // Docker镜像名称 — defaults to the ACR login server above; override DOCKER_REGISTRY to point elsewhere
        DOCKER_REGISTRY  = "${env.DOCKER_REGISTRY ?: ACR_NAME + '.azurecr.io'}"
        BACKEND_IMAGE    = "${DOCKER_REGISTRY}/fraudshield-backend"
        FRONTEND_IMAGE   = "${DOCKER_REGISTRY}/fraudshield-frontend"
        // BUILD_NUMBER is unique per run, so each deploy gets a genuinely new image tag —
        // Azure Container Apps then auto-creates a new revision, no manual --revision-suffix needed
        IMAGE_TAG        = "${env.BUILD_NUMBER}"
        MAVEN_OPTS       = "-Xmx512m -XX:+TieredCompilation"
    }

    tools {
        maven 'Maven-3.9'
        jdk   'JDK-21'
    }

    options {
        // 保留最近10次构建记录
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // 构建超时60分钟
        timeout(time: 60, unit: 'MINUTES')
        // 同一分支不允许并发构建
        disableConcurrentBuilds()
        // 时间戳前缀
        timestamps()
    }

    stages {

        // ── Stage 1: Checkout ─────────────────────────────────────────────
        stage('Checkout') {
            steps {
                echo "Building branch: ${env.BRANCH_NAME ?: 'unknown'}, build #${BUILD_NUMBER}"
                checkout([$class: 'GitSCM',
                    branches: scm.branches,
                    extensions: [[$class: 'CloneOption', timeout: 30]],
                    userRemoteConfigs: scm.userRemoteConfigs
                ])
            }
        }

        // ── Stage 2: Code Quality (Checkstyle + SpotBugs in parallel) ─────
        // 并行运行代码质量检查，节省时间
        stage('Code Quality') {
            parallel {

                stage('Checkstyle') {
                    steps {
                        echo 'Running Checkstyle...'
                        sh 'mvn checkstyle:check -q'
                    }
                    post {
                        always {
                            // Warnings Next Generation 插件显示Checkstyle报告
                            recordIssues(
                                enabledForFailure: true,
                                tool: checkStyle(pattern: '**/target/checkstyle-result.xml')
                            )
                        }
                    }
                }

                stage('SpotBugs') {
                    steps {
                        echo 'Running SpotBugs...'
                        // spotbugs:check compiles first; -DskipTests to avoid double compile
                        sh 'mvn spotbugs:check -DskipTests -q'
                    }
                    post {
                        always {
                            recordIssues(
                                enabledForFailure: true,
                                tool: spotBugs(pattern: '**/target/spotbugsXml.xml')
                            )
                        }
                    }
                }
            }
        }

        // ── Stage 3: Unit Tests + Coverage ────────────────────────────────
        stage('Unit Tests') {
            steps {
                echo 'Running unit tests (Surefire — excludes *IT.java)...'
                sh 'mvn test -q'
            }
            post {
                always {
                    // JUnit报告
                    junit '**/target/surefire-reports/*.xml'
                    // JaCoCo覆盖率报告 (需要安装JaCoCo Jenkins插件)
                    jacoco(
                        execPattern:    '**/target/jacoco.exec',
                        classPattern:   '**/target/classes',
                        sourcePattern:  '**/src/main/java',
                        minimumLineCoverage:   '70',
                        minimumBranchCoverage: '60'
                    )
                }
            }
        }

        // ── Stage 4: Integration Tests ─────────────────────────────────────
        stage('Integration Tests') {
            steps {
                echo 'Running integration tests (Failsafe — *IT.java)...'
                sh 'mvn verify -DskipTests -Dcheckstyle.skip=true -Dspotbugs.skip=true -q'
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: '**/target/failsafe-reports/*.xml'
                    // Merged unit + IT coverage report
                    jacoco(
                        execPattern:    '**/target/jacoco-merged.exec',
                        classPattern:   '**/target/classes',
                        sourcePattern:  '**/src/main/java',
                        minimumLineCoverage:   '70',
                        minimumBranchCoverage: '60'
                    )
                }
            }
        }

        // ── Stage 5: Build JAR ────────────────────────────────────────────
        stage('Build JAR') {
            steps {
                echo 'Packaging JAR (skipping tests — already run above)...'
                sh 'mvn package -DskipTests -q'
            }
            post {
                success {
                    // 归档jar包，方便下载
                    archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                }
            }
        }

        // ── Stage 6: Build Docker Images ──────────────────────────────────
        stage('Build Docker Images') {
            steps {
                echo 'Building Docker images...'
                sh """
                    # Backend image (multi-stage: maven → jre-alpine)
                    docker build -t ${BACKEND_IMAGE}:${IMAGE_TAG} \
                                 -t ${BACKEND_IMAGE}:latest \
                                 .

                    # Frontend image (multi-stage: node → nginx-alpine)
                    docker build -t ${FRONTEND_IMAGE}:${IMAGE_TAG} \
                                 -t ${FRONTEND_IMAGE}:latest \
                                 ./fraudshield-frontend
                """
            }
        }

        // ── Stage 7: Push to Registry (main branch only) ──────────────────
        // 只有main分支才推送镜像到注册表
        stage('Push to Registry') {
            when {
                anyOf {
                    branch 'main'
                    environment name: 'FORCE_PUSH', value: 'true'
                }
            }
            steps {
                echo 'Pushing images to registry...'
                script {
                    if (env.DOCKER_REGISTRY) {
                        // ACR admin credentials (resource-level RBAC) — not an Azure AD service
                        // principal, so this works even in tenants that block self-service app
                        // registration (e.g. university/student tenants). Fetch once with:
                        //   az acr credential show --name <ACR_NAME> --query "passwords[0].value" -o tsv
                        withCredentials([string(credentialsId: 'acr-admin-password', variable: 'ACR_PASSWORD')]) {
                            sh """
                                echo "\$ACR_PASSWORD" | docker login ${DOCKER_REGISTRY} -u ${ACR_NAME} --password-stdin
                                docker push ${BACKEND_IMAGE}:${IMAGE_TAG}
                                docker push ${BACKEND_IMAGE}:latest
                                docker push ${FRONTEND_IMAGE}:${IMAGE_TAG}
                                docker push ${FRONTEND_IMAGE}:latest
                            """
                        }
                    } else {
                        echo 'DOCKER_REGISTRY not set — skipping push (local build only)'
                    }
                }
            }
        }

        // ── Stage 8: Deploy ────────────────────────────────────────────────
        // CI's job ends at publishing a tested, versioned image to the registry above.
        // Deploying that image to Azure Container Apps is a deliberate manual/gated step —
        // our Azure tenant (university/student) blocks creating the service principal Jenkins
        // would need for unattended `az containerapp update`, and separating "CI publishes" from
        // "a human approves production deploys" is standard practice anyway. After a green
        // build, deploy with:
        //   az login
        //   ./deploy/deploy-azure.sh <BUILD_NUMBER>
        // (DEPLOY_TARGET=local/aws below remain available for non-Azure targets.)
        stage('Deploy') {
            when {
                anyOf {
                    branch 'main'
                    environment name: 'FORCE_DEPLOY', value: 'true'
                }
            }
            steps {
                script {
                    if (env.DEPLOY_TARGET == 'azure') {
                        echo "Image pushed as ${BACKEND_IMAGE}:${IMAGE_TAG} / ${FRONTEND_IMAGE}:${IMAGE_TAG}."
                        echo "Azure deploy is a manual step — run: az login && ./deploy/deploy-azure.sh ${IMAGE_TAG}"
                    } else if (env.DEPLOY_TARGET == 'aws') {
                        sh './deploy/deploy-aws.sh ${IMAGE_TAG}'
                    } else {
                        // 默认：本地Docker Compose部署
                        sh './deploy/deploy-local.sh ${IMAGE_TAG}'
                    }
                }
            }
        }

    } // end stages

    post {
        success {
            echo "✅ Pipeline #${BUILD_NUMBER} succeeded on branch ${env.BRANCH_NAME ?: 'unknown'}"
        }
        failure {
            echo "❌ Pipeline #${BUILD_NUMBER} FAILED — check logs above"
        }
        always {
            echo "Build ${BUILD_NUMBER} finished. Cleaning workspace..."
            cleanWs()
        }
    }
}
