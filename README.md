# jenkinslib
Jenkins share library

// 这个 pipeline_libraries 需要再jenkins里面配置一下，搜索lib 然后配置
## 导入库 @Library('pipeline_libraries') _

## 使用
```
def tools = new org.devops.tools() 
实例化一个类，然后调用里面的方法

如果是var 调用方法 需要import

```