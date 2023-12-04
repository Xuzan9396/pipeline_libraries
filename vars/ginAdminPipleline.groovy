def call(Map params){
    pipeline {
        agent any
        options {
            disableConcurrentBuilds()
            timeout(time: 8, unit: 'MINUTES')
        }
    environment {

        CREDENTIALSID = "${params.CREDENTIALSID}"
        VERSION_FILE = "${params.VERSION_FILE}"
        CURL_URL = "${params.CURL_URL ? params.CURL_URL : ''}"
        CURL_SLEEP = "${params.CURL_SLEEP ? params.CURL_SLEEP : '0'}"
    }

        stages {

            stage('判断构建方式:') {
                steps {
                    script {

                          CAUSE = "${currentBuild.getBuildCauses()[0].shortDescription}"
                          println("构建方式 ${CAUSE}")
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



            stage('切换分支') {
                steps {
                    script {
                        // 尝试获取当前分支名
                        def branchName = ''
                        try {
                           branchName = env.GIT_BRANCH.split('/')[-1]

                        } catch(Exception e) {
                            echo "Error getting branch name: ${e.getMessage()}"
                            currentBuild.result = 'ABORTED'
                            error("Failed to get branch name.")
                        }

                        env.BRANCHNAME = branchName
                        println("现在使用的是BRANCHNAME: ${env.BRANCHNAME}")
                        if (branchName == 'test') {
                            echo "这是test分支"
                            // 如果需要，可以在这里检出
                        } else if (branchName == 'main') {
                            echo "这是main分支"
                            // 如果需要，可以在这里检出
                        } else {
                            echo "未知分支：${branchName}"
                            currentBuild.result = 'ABORTED'
                            error("未知分支：${branchName}")
                        }
                    }
                }
            }

            stage('开始计算版本:') {
                steps {
                    script {
                        env.BRANCH_FILE_VERSION = env.BRANCHNAME + "_" + env.VERSION_FILE
                        println("VERSION_FILE: ${env.BRANCH_FILE_VERSION}")
                        if (fileExists(env.BRANCH_FILE_VERSION)) {
                            def versions = readFile(file: env.BRANCH_FILE_VERSION).trim().split("\n")
                            env.LATEST_VERSION = versions[-1]
                            env.PREVIOUS_VERSION = versions.size() > 1 ? versions[-2] : "v0.0.1"
                        } else {
                            env.LATEST_VERSION = "v0.0.1"
                            env.PREVIOUS_VERSION = "v0.0.1"
                        }
                    }
                }
            }

            stage('读取git commit信息') {
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
                                env.DIR_RUN = "cd /root/.jenkins/workspace/gin-vue-admin/server && ./pro_build.sh"

                            } else if (lastCommitMessage.startsWith("web#pro")) {
                                env.OPERATION = "deploy_web"
                                env.VERSION = "v0.0.${env.BUILD_NUMBER}"
                                env.DIR_RUN = "cd /root/.jenkins/workspace/gin-vue-admin/web && ./pro_build.sh"

                            }else if (lastCommitMessage.startsWith("#conf")){
                                env.OPERATION = "conf"
                                env.VERSION = "v0.0.${env.BUILD_NUMBER}"
                                env.DIR_RUN = "cd /root/.jenkins/workspace/gin-vue-admin/server && ./pro_build.sh"

                            } else {
                                currentBuild.result = 'ABORTED'
                                error("Invalid commit message. Either start with #pro for deploy or #pre for rollback!")
                            }
                        }


                    }
                }
            }

            stage('Build 程序') {
                when {
                    expression { env.OPERATION in ["deploy", "deploy_web"] }
                }
                steps {
                    script {
                    if (env.OPERATION == "deploy") {
                        sh '''
                           cd ./server
                           export GOPROXY=https://goproxy.cn,direct
                           /usr/local/go/bin/go mod tidy
                           CGO_ENABLED=0 GOOS=linux GOARCH=amd64 /usr/local/go/bin/go build -x -ldflags "-s -w" -o ./local_news_gva ./main.go
                         '''
                    }else if (env.OPERATION == "deploy_web") {
                       sh '''
                            export PATH=/usr/local/node-v20.10.0/bin:$PATH
                            cd ./web
                            npm install -g vite
                            npm run build
                        '''
                    }

                    }
                }
            }

            stage('登录服务器发布') {
                steps {
                    script {
                       sh(script: "${DIR_RUN} ${env.BRANCHNAME} ${VERSION} ${env.OPERATION}")

                    }
                }
            }

            stage('验证接口') {
                when {
                   expression { env.CURL_URL != ""  && env.CURL_SLEEP.toInteger() > 0 }
               }
                steps {

                    script {
                        sleep env.CURL_SLEEP.toInteger()
                        println("睡眠${env.CURL_SLEEP}s,在验证http!")
                        // -k 禁用证书验证
                        sh """
                            status_code=\$(curl -k -o /dev/null -s -w "%{http_code}" ${CURL_URL})
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
                        if (fileExists(env.BRANCH_FILE_VERSION) && sh(script: "tail -c 1 ${env.BRANCH_FILE_VERSION} | wc -l", returnStdout: true).trim() != "1") {
                            sh "echo '' >> ${env.BRANCH_FILE_VERSION}"
                        }
                        sh "echo '${VERSION}' >> ${env.BRANCH_FILE_VERSION}"
                    }
                    currentBuild.description = "构建成功！"
                    def projectName = sh(script: "basename `git rev-parse --show-toplevel`", returnStdout: true).trim()
                    def messageToSend = "${projectName}:${env.BRANCHNAME} ${VERSION} ${env.CommitMessage} commit_id: ${env.GIT_COMMIT}"
//                     messageToSend = messageToSend.replaceAll("#", "") // 去除所有的#字符
                    println("messageToSend: ${messageToSend}")
                    sh "ssh target '/home/ec2-user/data/docker/services/tg.sh \"构建成功 ${messageToSend}\"'"
                }
            }
            failure {
                echo 'Build failed!'
                script {
                    currentBuild.description = "构建失败！"
                    def projectName = sh(script: "basename `git rev-parse --show-toplevel`", returnStdout: true).trim()
                    def messageToSend = "${projectName}:${env.BRANCHNAME} ${VERSION} ${env.CommitMessage}"
//                     messageToSend = messageToSend.replaceAll("#", "") // 去除所有的#字符
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