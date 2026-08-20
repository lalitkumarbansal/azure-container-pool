# azure-container-pool

- Create a resource group
- Create a vnet with subnet 
- Delegate the subnet to container group
- Assign the roles to as per following page at resource group level
- https://learn.microsoft.com/en-us/azure/container-instances/container-instances-standby-pool-configure-permissions
- Also assign the Reader permission at the RG level otherwise you will have to give permission later after profile creation
```
az role assignment create \
--assignee-object-id 3a06b027-a664-42f8-bd87-52a75c210bdc \
--assignee-principal-type ServicePrincipal \
--role Reader \
--scope "/subscriptions/{subscription-Id}/resourceGroups/{ResourrceGrpName}/providers/Microsoft.ContainerInstance/containerGroupProfiles/{ProfileName}"
```


# Run the java program
``` mvn clean compile exec:java ```

<img width="944" height="845" alt="image" src="https://github.com/user-attachments/assets/515b1a5b-33b7-4edb-a9d5-824ffcfcae57" />



# Important commands
**Delete Pool**
```
az standby-container-group-pool delete --resource-group rg-ramco --name my-aci-vnet-pool-20-aug --yes
```
 
**Delete Profile**
```
az resource delete   --resource-group rg-ramco   --name myAciProfile20Aug   --resource-type Microsoft.ContainerInstance/containerGroupProfiles
``` 
**Status**
```
az standby-container-group-pool status   -g rg-ramco   -n my-aci-vnet-pool-20-aug
```
