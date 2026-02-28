```
Could not determine the dependencies of task ':prepareSandbox'.
> Failed to query the value of task ':prepareSandbox' property 'pluginDependencies'.
   > Cannot find builtin plugin 'Kotlin' for IDE: C:\Users\pc\.gradle\caches\modules-2\files-2.1\com.jetbrains.intellij.idea\ideaIC\2025.3.1\9a0643e8a9af9981748acdf1d6b4a3f873fed389\ideaIC-2025.3.1
```
```
C:\Users\pc\.gradle\caches\modules-2\files-2.1\com.jetbrains.intellij.idea\ideaIC\2025.1.3\4f0a594c8d3327ec4113705c1167f78a62293e8d\ideaIC-2025.1.3
```


需要在上面目录下的文件 plugins/builtinRegistry-1.xml 加载插件：
```xml
<plugin directoryName="Kotlin" id="org.jetbrains.kotlin">
    <dependencies>
      <dependency>com.intellij.modules.platform</dependency>
      <dependency>com.intellij.modules.java</dependency>
      <dependency>com.intellij.modules.java-capable</dependency>
      <dependency>com.intellij.java</dependency>
    </dependencies>
  </plugin>
```