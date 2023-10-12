package org.devops

//格式化输出
def PrintMes(value,color){
    colors = ['red'   : "\033[40;31m >>>>>>>>>>>${value}<<<<<<<<<<< \033[0m",
              'blue'  : "\033[47;34m ${value} \033[0m",
              'green' : "[1;32m>>>>>>>>>>${value}>>>>>>>>>>[m",
              'green1' : "\033[40;32m >>>>>>>>>>>${value}<<<<<<<<<<< \033[0m" ]
    ansiColor('xterm') {
        println(colors[color])
    }
}


def RunBuild(Map params){
    pipeline {
        agent any
        options {
            disableConcurrentBuilds()
            timeout(time: 8, unit: 'MINUTES')
        }
        environment {
            CRAWLER_API = params.CRAWLER_API
            CRAWLER_API_GITHUB = params.CRAWLER_API_GITHUB
            DIR_RUN = params.DIR_RUN
            CREDENTIALSID = params.CREDENTIALSID
            VERSION_FILE = params.VERSION_FILE
            CURL_URL = params.CURL_URL
            CURL_SLEEP = params.CURL_SLEEP
        }

        stages {

            stage('判断构建方式:') {
                steps {
                    script {

                          CAUSE = "${currentBuild.getBuildCauses()[0].shortDescription}"
                          println("构建方式 ${CAUSE}")
    //                       echo " BUILD_USER: ${env.BUILD_USER}"
    //                       echo "Build user ID: ${env.BUILD_USER_ID}"
                           if (CAUSE.toLowerCase().contains("github"))  {
                             env.IS_MANUAL_TRIGGER = "false"
                             echo "自动push构建！"

                          } else {
                              env.IS_MANUAL_TRIGGER = "true"
                              echo "手动构建！"
                          }
                    }
                }
            }

            stage('开始计算版本:') {
                steps {
                    script {
                        if (fileExists(env.VERSION_FILE)) {
                            def versions = readFile(file: env.VERSION_FILE).trim().split("\n")
                            env.LATEST_VERSION = versions[-1]
                            env.PREVIOUS_VERSION = versions.size() > 1 ? versions[-2] : "v0.0.1"
                        } else {
                            env.LATEST_VERSION = "v0.0.1"
                            env.PREVIOUS_VERSION = "v0.0.1"
                        }
                    }
                }
            }

            stage('切换main分支') {
                steps {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/main']],
                        extensions: [],
                        userRemoteConfigs: [[url: "git@github.com:Xuzan9396/${CRAWLER_API_GITHUB}.git", credentialsId: "${CREDENTIALSID}"]]
                    ])
                }
            }

            stage('读取版本号——gitlog信息') {
                steps {
                    script {
                        if (env.IS_MANUAL_TRIGGER == "true") {
                            env.OPERATION = "deploy"
                            env.VERSION = "v0.0.${env.BUILD_NUMBER}"
                        } else {

                            def lastCommitMessage = sh(script: "git log -1 --pretty=%B", returnStdout: true).trim()
                            env.CommitMessage = lastCommitMessage
                            if (lastCommitMessage.startsWith("#pro")) {
                                env.OPERATION = "deploy"
                                env.VERSION = "v0.0.${env.BUILD_NUMBER}"
                            } else if (lastCommitMessage.startsWith("#pre")) {
                                env.OPERATION = "rollback"
                                env.VERSION = env.PREVIOUS_VERSION
                            } else {
                                currentBuild.result = 'ABORTED'
                                error("Invalid commit message. Either start with #pro for deploy or #pre for rollback!")
                            }
                        }


                    }
                }
            }

            stage('Build Docker,推送 Image and Push') {
                when {
                    expression { env.OPERATION == "deploy" }
                }
                steps {
                    script {
                        sh 'docker build --platform linux/amd64 -t gitxuzan/${CRAWLER_API}:${VERSION} -f Dockerfile_amd64_arm64 .'
                        sh 'docker push gitxuzan/${CRAWLER_API}:${VERSION}'
                    }
                }
            }

            stage('登录服务器发布') {
                steps {
                    script {
                        sh 'ssh -t target "${DIR_RUN} ${VERSION}"'
                    }
                }
            }

            stage('验证接口') {
                steps {
                    when {
                       expression { env.CURL_SLEEP > 0 }
                   }
                    script {
                        sleep 5
                        sh """
                            status_code=\$(curl -o /dev/null -s -w "%{http_code}" ${CURL_URL})
                            if [ "\$status_code" != "200" ]; then
                                echo "API check failed! Received status code: \$status_code"
                                exit 1
                            fi
                        """
                    }
                }
            }



        }

        post {
            success {
                echo 'Build was successful!'
                script {
                    if (env.OPERATION == "deploy") {
                        if (fileExists(env.VERSION_FILE) && sh(script: "tail -c 1 ${env.VERSION_FILE} | wc -l", returnStdout: true).trim() != "1") {
                            sh "echo '' >> ${env.VERSION_FILE}"
                        }
                        sh "echo '${VERSION}' >> ${env.VERSION_FILE}"
                    }
                    currentBuild.description = "构建成功！"
                    def projectName = sh(script: "basename `git rev-parse --show-toplevel`", returnStdout: true).trim()
                    def messageToSend = "${projectName}: ${VERSION} ${env.CommitMessage}"
                    sh "ssh target '/home/ec2-user/data/docker/services/tg.sh \"构建成功 ${messageToSend}\"'"
                }
            }
            failure {
                echo 'Build failed!'
                script {
                    currentBuild.description = "构建失败！"
                    def projectName = sh(script: "basename `git rev-parse --show-toplevel`", returnStdout: true).trim()
                    def messageToSend = "${projectName}: ${VERSION} ${env.CommitMessage}"
                    sh "ssh target '/home/ec2-user/data/docker/services/tg.sh \"构建失败 ${messageToSend}\"'"
                }
            }
            aborted {
                echo '构建取消拉aborted!'
                script {
                    currentBuild.description = "构建取消拉！"
                }
            }
        }
    }

}