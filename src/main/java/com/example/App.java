package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.azure.core.credential.TokenCredential;
import com.azure.core.management.Region;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.models.AzureCloud;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.resources.ResourceManager;
import com.azure.resourcemanager.resources.models.GenericResource;
import com.azure.resourcemanager.standbypool.StandbyPoolManager;
import com.azure.resourcemanager.standbypool.models.ContainerGroupProfile;
import com.azure.resourcemanager.standbypool.models.ContainerGroupProperties;
import com.azure.resourcemanager.standbypool.models.DynamicSizing;
import com.azure.resourcemanager.standbypool.models.RefillPolicy;
import com.azure.resourcemanager.standbypool.models.StandbyContainerGroupPoolElasticityProfile;
import com.azure.resourcemanager.standbypool.models.StandbyContainerGroupPoolResource;
import com.azure.resourcemanager.standbypool.models.StandbyContainerGroupPoolResourceProperties;
import com.azure.resourcemanager.standbypool.models.StandbyContainerGroupPoolResourceUpdateProperties;
import com.azure.resourcemanager.standbypool.models.Subnet;


public class App {
;

	public static void main(String[] args) {
		// Change these to match your actual Azure resources
		String subscriptionId = "68b49ef1-514f-4d17-9849-26b3e9e1a838";
		String resourceGroupName = "rg-ramco";
		String poolName = "my-aci-vnet-pool-20-aug";
		String containerGroupName = "mycontainergroup";
		String location = "swedencentral";
		String profileName = "myAciProfile20Aug";
		String vnet = "ramco-vnet-20aug";
		String subnet = "default";

		System.out.println("Connecting to Azure...");

		// Authenticates using your environment variables or local Azure CLI session
		TokenCredential credential = new DefaultAzureCredentialBuilder().build();
		AzureProfile profile = new AzureProfile("", subscriptionId, AzureCloud.AZURE_PUBLIC_CLOUD);

		System.out.println("Authenticated successfully. Subscription ID: " + subscriptionId);

		ResourceManager resourceManager = ResourceManager.authenticate(credential, profile).withDefaultSubscription();

		String subnetID = "/subscriptions/" + subscriptionId + "/resourceGroups/"+ resourceGroupName +"/providers/Microsoft.Network/virtualNetworks/"+vnet+"/subnets/"+subnet;
		System.out.println("Subnet ID " + subnetID);
		
		GenericResource containerGroupProfile = createContainerGroupProfile(resourceManager, subscriptionId,
				resourceGroupName, profileName, location, subnetID);

		System.out.println("Container group profile created or retrieved: " + containerGroupProfile.id());

		long profileRevision = getProfileRevision(containerGroupProfile);

		StandbyPoolManager standbyPoolManager = StandbyPoolManager.authenticate(credential, profile);

		
		
		StandbyContainerGroupPoolResource pool = getOrCreateStandbyPool(standbyPoolManager, resourceGroupName, poolName,
				location, containerGroupProfile.id(), profileRevision, subnetID );

		waitForReadyContainer(resourceManager, pool.id());


		for (int i=0; i<20; i++) {
		
		GenericResource containerGroup = createContainerFromPool(resourceManager, subscriptionId, resourceGroupName,
				containerGroupName+"-"+i, location, containerGroupProfile.id(), profileRevision, pool.id(), subnetID);
			System.out.println("---------------------------------");
			System.out.println("Container group successfully created from pool: " + containerGroup.id());
			System.out.println("---------------------------------");
			//deleteContainer(resourceManager, containerGroupName+i, resourceGroupName);
		}
		
		for (int i = 0; i < 20; i++) {

			GenericResource containerGroup1 = createContainerFromPool(resourceManager, subscriptionId,
					resourceGroupName, containerGroupName + "-" + i, location, containerGroupProfile.id(),
					profileRevision, pool.id(), subnetID);
			System.out.println("Container group successfully created from pool: " + containerGroup1.id());
			deleteContainer(resourceManager, containerGroupName + "-" + i, resourceGroupName);
		}
	}

	





	private static StandbyContainerGroupPoolResource getOrCreateStandbyPool(StandbyPoolManager standbyPoolManager,
			String resourceGroupName, String poolName, String location, String profileId, long profileRevision,
			String subnetid) {
		System.out.println("Looking for existing ACI standby pool: " + poolName);
		System.out.println("Updating ACI standby pool to profile revision " + profileRevision + ": " + poolName);
		List<Subnet> subnetIds = new ArrayList<Subnet>();
		Subnet subnet = new Subnet().withId(subnetid);
		subnetIds.add(subnet);
		
		try {
			StandbyContainerGroupPoolResource existingPool = standbyPoolManager.standbyContainerGroupPools()
					.getByResourceGroup(resourceGroupName, poolName);

			System.out.println("Found existing ACI standby pool: " + existingPool);

			ContainerGroupProfile configuredProfile = existingPool.properties().containerGroupProperties()
					.containerGroupProfile();
			if (profileId.equalsIgnoreCase(configuredProfile.id())
					&& Long.valueOf(profileRevision).equals(configuredProfile.revision())) {
				System.out.println("Using existing ACI standby pool: " + poolName);
				return existingPool;
			}

			

			return existingPool.update()
					.withProperties(new StandbyContainerGroupPoolResourceUpdateProperties()
							.withContainerGroupProperties(new ContainerGroupProperties().withSubnetIds(subnetIds)
									.withContainerGroupProfile(new ContainerGroupProfile().withId(profileId)
											.withRevision(profileRevision)))
							.withElasticityProfile(new StandbyContainerGroupPoolElasticityProfile()
									.withMaxReadyCapacity(10L).withDynamicSizing(new DynamicSizing().withEnabled(true))
									.withRefillPolicy(RefillPolicy.ALWAYS))

					).apply();
		} catch (ManagementException exception) {
			if (exception.getResponse() == null || exception.getResponse().getStatusCode() != 404) {
				throw exception;
			}
		}

		System.out.println("Creating ACI standby pool: " + poolName);
		StandbyContainerGroupPoolResource pool = standbyPoolManager.standbyContainerGroupPools().define(poolName)
				.withRegion(Region.fromName(location)).withExistingResourceGroup(resourceGroupName)
				.withProperties(new StandbyContainerGroupPoolResourceProperties()
						.withContainerGroupProperties(new ContainerGroupProperties().withSubnetIds(subnetIds)
								.withContainerGroupProfile(
								new ContainerGroupProfile().withId(profileId).withRevision(profileRevision)))
						.withElasticityProfile(new StandbyContainerGroupPoolElasticityProfile().withMaxReadyCapacity(5L)
								.withRefillPolicy(RefillPolicy.ALWAYS)))
				.create();

		System.out.println("Pool successfully deployed! Resource ID: " + pool.id());
		return pool;
	}

	private static void waitForReadyContainer(ResourceManager resourceManager, String standbyPoolId) {
		String apiVersion = "2025-10-01";
		String runtimeViewId = standbyPoolId + "/runtimeViews/latest";
		int maxAttempts = 20;

		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			GenericResource runtimeView = resourceManager.genericResources().getById(runtimeViewId, apiVersion);
			if (getInstanceCount(runtimeView, "Running") > 0) {
				System.out.println("Standby container is ready.");
				return;
			}

			System.out.println("Waiting for standby pool refill (attempt " + attempt + "/" + maxAttempts + ")...");
			try {
				Thread.sleep(12_000);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for standby pool refill", exception);
			}
		}

		throw new IllegalStateException("Standby pool did not produce a running container within 10 minutes");
	}

	private static long getInstanceCount(GenericResource runtimeView, String requestedState) {
		if (!(runtimeView.properties() instanceof Map<?, ?> properties)
				|| !(properties.get("instanceCountSummary") instanceof List<?> summaries)) {
			return 0;
		}

		for (Object summaryValue : summaries) {
			if (!(summaryValue instanceof Map<?, ?> summary)
					|| !(summary.get("instanceCountsByState") instanceof List<?> counts)) {
				continue;
			}
			for (Object countValue : counts) {
				if (countValue instanceof Map<?, ?> count
						&& requestedState.equalsIgnoreCase(String.valueOf(count.get("state")))
						&& count.get("count") instanceof Number value) {
					return value.longValue();
				}
			}
		}
		return 0;
	}

	private static GenericResource createContainerGroupProfile(ResourceManager resourceManager, String subscriptionId,
			String resourceGroupName, String profileName, String location, String subnetID) {
		String apiVersion = "2023-05-01";
		String profileId = "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroupName
				+ "/providers/Microsoft.ContainerInstance/containerGroupProfiles/" + profileName;

		System.out.println("Looking for existing ACI container group profile: " + profileName);

		try {
			GenericResource existingProfile = resourceManager.genericResources().getById(profileId, apiVersion);
			System.out.println("Using existing ACI container group profile: " + profileName);
			return existingProfile;
		} catch (ManagementException exception) {
			if (exception.getResponse() == null || exception.getResponse().getStatusCode() != 404) {
				throw exception;
			}
		}

		System.out.println("Creating ACI container group profile: " + profileName);
		Map<String, Object> properties = Map.of("containers",
				List.of(Map.of("name", "nginx", "properties",
						Map.of("image", "nginx:latest", "ports", List.of(Map.of("port", 80)), "resources",
								Map.of("requests", Map.of("cpu", 0.5, "memoryInGB", 1))))),
				"restartPolicy", "Always", 
				"ipAddress",Map.of("type", "Private", "ports", List.of(Map.of("protocol", "TCP", "port", 80))),
				//"subnetIds",List.of(Map.of("id", subnetID)),
				"osType", "Linux");

		resourceManager.genericResources().define(profileName).withRegion(location)
				.withExistingResourceGroup(resourceGroupName).withResourceType("containerGroupProfiles")
				.withProviderNamespace("Microsoft.ContainerInstance").withoutPlan().withApiVersion(apiVersion)
				.withProperties(properties).create();

		return resourceManager.genericResources().getById(profileId, apiVersion);
	}

	private static GenericResource createContainerFromPool(ResourceManager resourceManager, String subscriptionId,
			String resourceGroupName, String containerGroupName, String location, String profileId,
			long profileRevision, String standbyPoolId, String SubnetID) {
		String apiVersion = "2025-09-01";
		int maxReservationAttempts = 6;
		/*
		String containerGroupId = "/subscriptions/" + subscriptionId + "/resourceGroups/" + resourceGroupName
				+ "/providers/Microsoft.ContainerInstance/containerGroups/" + containerGroupName;

		if (resourceManager.genericResources().checkExistenceById(containerGroupId, apiVersion)) {
			System.out.println("Using existing container group: " + containerGroupName);
			return resourceManager.genericResources().getById(containerGroupId, apiVersion);
		}

		System.out.println("Creating container group from standby pool: " + containerGroupName);
		*/
		Map<String, Object> properties = Map.of("location",location,
				"properties", Map.of("containers",
						List.of(
								Map.of("name", "nginx" 
										,"properties", Map.of("configMap", Map.of("keyValuePairs", Map.of("source", "standby-pool-demo")))
						)
						
						)),
						"containerGroupProfile", Map.of("id", profileId, "revision", profileRevision), 
						"standbyPoolProfile",Map.of("id", standbyPoolId, "failContainerGroupCreateOnReuseFailure", true),
						"subnetIds",List.of(Map.of("id", SubnetID))
						

				);

		
		for (int attempt = 1; attempt <= maxReservationAttempts; attempt++) {
			try {
				return resourceManager.genericResources().define(containerGroupName).withRegion(location)
						.withExistingResourceGroup(resourceGroupName).withResourceType("containerGroups")
						.withProviderNamespace("Microsoft.ContainerInstance").withoutPlan().withApiVersion(apiVersion)
						.withProperties(properties).create();
			} catch (ManagementException exception) {

                System.out.println("*************************");
                System.out.println("Management Execqption occurred while creating container group: " + exception.getMessage());
                System.out.println("*************************");

				if (!isInsufficientStandbyCapacity(exception) || attempt == maxReservationAttempts) {
					throw exception;
				}

				System.out.println("No standby container is currently available for " + containerGroupName
						+ "; waiting for the pool to refill before retry " + (attempt + 1) + "/"
						+ maxReservationAttempts + ".");
				waitForReadyContainer(resourceManager, standbyPoolId);
			}
		}

		throw new IllegalStateException("Container group reservation attempts were exhausted");
	}

	private static boolean isInsufficientStandbyCapacity(ManagementException exception) {
		if (exception.getResponse() == null || exception.getResponse().getStatusCode() != 400) {
			return false;
		}

		String message = exception.getMessage();
		return message != null
				&& message.contains("StandyPoolReservationFailed")
				&& message.contains("InsufficientContainerGroups");
	}

	private static void deleteContainer(ResourceManager resourceManager, String containerName,
			String resourceGroupName) {
		String apiVersion = "2025-09-01";
		String containerGroupId = "/subscriptions/" + resourceManager.subscriptionId() + "/resourceGroups/"
				+ resourceGroupName + "/providers/Microsoft.ContainerInstance/containerGroups/" + containerName;

		System.out.println("Deleting container group: " + containerName);
		try {
			resourceManager.genericResources().deleteById(containerGroupId, apiVersion);
			System.out.println("Container group deleted: " + containerName);
		} catch (ManagementException exception) {
			if (exception.getResponse() != null && exception.getResponse().getStatusCode() == 404) {
				System.out.println("Container group does not exist: " + containerName);
				return;
			}
			throw exception;
		}
	}

	private static long getProfileRevision(GenericResource profile) {
		if (profile.properties() instanceof Map<?, ?> properties
				&& properties.get("revision") instanceof Number revision) {
			System.out.println(
					"Container group profile ready: " + profile.id() + " (revision " + revision.longValue() + ")");
			return revision.longValue();
		}

		throw new IllegalStateException("Azure did not return a revision for profile " + profile.name());
	}
}
