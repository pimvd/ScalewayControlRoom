# The kit reads Scaleway's JSON as plain JsonElement; only ApiKey is
# serialized (the key store), through its generated serializer.
-keepclassmembers class com.pvd.scwcontrolroom.kit.ApiKey { *; }
-keep class com.pvd.scwcontrolroom.kit.ApiKey$$serializer { *; }
