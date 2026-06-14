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
        // Docker镜像名称 — 覆盖时传入 DOCKER_REGISTRY env var
        // Override DOCKER_REGISTRY with your ACR/ECR URI for cloud deployments
        DOCKER_REGISTRY  = "${env.DOCKER_REGISTRY ?: ''}"
        BACKEND_IMAGE    = "${env.DOCKER_REGISTRY ? env.DOCKER_REGISTRY + '/' : ''}fraudshield-backend"
        FRONTEND_IMAGE   = "${env.DOCKER_REGISTRY ? env.DOCKER_REGISTRY + '/' : ''}fraudshield-frontend"
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
                checkout scm
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
                sh 'mvn failsafe:integration-test failsafe:verify jacoco:merge-results jacoco:report-merged -q'
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
                        // Azure Container Registry 登录
                        // For ACR: az acr login --name <registry-name>
                        // For AWS ECR: aws ecr get-login-password | docker login ...
                        withCredentials([string(credentialsId: 'docker-registry-password',
                                                variable: 'REGISTRY_PASS')]) {
                            sh """
                                echo "${REGISTRY_PASS}" | \
                                docker login ${DOCKER_REGISTRY} -u \$REGISTRY_USER --password-stdin
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

        // ── Stage 8: Deploy (main branch only) ────────────────────────────
        // 部署到本地Docker或云平台
        stage('Deploy') {
            when {
                anyOf {
                    branch 'main'
                    environment name: 'FORCE_DEPLOY', value: 'true'
                }
            }
            steps {
                echo 'Deploying...'
                script {
                    if (env.DEPLOY_TARGET == 'azure') {
                        // Azure Container Apps 部署
                        sh './deploy/deploy-azure.sh ${IMAGE_TAG}'
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
