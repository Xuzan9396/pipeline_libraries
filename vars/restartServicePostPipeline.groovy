def call() {
    pipeline {
        agent any
        parameters {
            string(name: 'webhookPayload', defaultValue: '', description: 'JSON payload received from webhook')
        }
        stages {
            stage('打印 Webhook 数据') {
                steps {
                    script {
                        if (webhookPayload == null || webhookPayload == '') {
                            error('No webhook payload received')
                        } else {
//                         println("所有body数据 --> ${webhookPayload}")
                            echo "Alert Name: ${webhookPayload_commonLabels_alertname}"
                            echo "Instance: ${webhookPayload_commonLabels_instance}"
                            echo "Job: ${webhookPayload_commonLabels_job}"
                            def jobName = webhookPayload_commonLabels_job ?: '' // 如果为空，则设置为 ''

                            switch (jobName) {
                                case 'widgets正式服':
                                    // 使用 sh 步骤执行 SSH 命令
                                    sh(script: """
                                    ssh -o ServerAliveInterval=20 -t widgets_go_3.230.6.94 "
                                        set -x &&
                                        (cd /data/widgets/ && nohup ./admin.sh restart > /dev/null 2>&1 &) &&
                                        exit
                                    "
                                """)
                                    break
                                case 'widgets测式服':
                                    // 使用 sh 步骤执行 SSH 命令
                                    sh(script: """
                                    ssh -o ServerAliveInterval=20 -t test_3.83.73.247 "
                                        set -x &&
                                        (cd /home/ec2-user/data/widgets/ && nohup ./admin.sh restart > /dev/null 2>&1 &) &&
                                          exit
                                    "
                                """)
                                    break
                                default:
                                    error('服务不存在不需要更新')

                            }
                        }
                    }
                }
            }
        }
        post {
            success {
                echo '构建成功!'
            }
            failure {
                echo '构建失败!'
            }
            aborted {
                echo '构建被取消!'
            }
        }
    }
}
