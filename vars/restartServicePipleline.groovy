def call(){
  pipeline {
      agent any
      options {
          disableConcurrentBuilds()
          timeout(time: 5, unit: 'MINUTES')
      }
      parameters {
          string(name: 'service_name', defaultValue: '', description: 'Name of the service')
          string(name: 'service_env', defaultValue: '', description: 'env')
      }
      stages {
          stage('获取服务和环境') {
              steps {
                  script {
                      echo "Service Name is ${params.service_name},env: ${params.service_env}"
                      if (params.service_name == '' || params.service_env == ''){
                          error('service_name is empty or service_env is empty')
                      }

                      if (!(params.service_name in ["widgets_api", "weather_api"])) {
                           error("service_env 参数必须是 'widgets_api' 或 'weather_api'")
                      }


                      if (!(params.service_env in ["main", "test"])) {
                          error("service_env 参数必须是 'main' 或 'test'")
                      }
                      echo "认证通过:Service Name is ${params.service_name},env: ${params.service_env}"

                      switch (value) {
                          case 'widgets_api':
                              if (params.service_env == 'main') {
                                      sh '''
                                         export GOPROXY=https://goproxy.cn,direct
                                         export GO111MODULE=on
                                         /usr/local/go/bin/go mod tidy
                                         CGO_ENABLED=0 GOOS=linux GOARCH=amd64 /usr/local/go/bin/go build -x -ldflags "-s -w" -o ./weather_api ./main.go
                                       '''
                              } else if (params.service_env == 'test') {

                              }

                              break
                          case 'weather_api':
                                   sh '''

                                 '''
                              break
                          default:
                              error("service_env 参数必须是 'widgets_api' 或 'weather_api'")


                  }
              }
          }
      }


       post {
             success {
                 echo '构建成功!'
                 script {
                     currentBuild.description = "构建成功！"

                 }
             }
             failure {
                 echo '构建失败!'
                 script {
                     currentBuild.description = "构建失败！"
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