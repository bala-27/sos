/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package example.sos.rest.inventory.integration;

import example.sos.rest.inventory.Inventory;
import example.sos.rest.inventory.InventoryItem;
import example.sos.rest.inventory.InventoryItem.ProductId;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.cloud.client.hypermedia.RemoteResource;
import org.springframework.hateoas.MediaTypes;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.Resources;
import org.springframework.hateoas.mvc.TypeReferences.ResourcesType;
import org.springframework.http.RequestEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;

/**
 * @author Oliver Gierke
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogIntegration {

	private static final ResourcesType<Resource<ProductAdded>> PRODUCTS_ADDED = new ResourcesType<Resource<ProductAdded>>() {};

	private final RemoteResource catalogEvents;
	private final Inventory inventory;
	private final IntegrationRepository repository;
	private final RestOperations client;

	@Scheduled(fixedDelay = 5000)
	public void updateProducts() {

		log.info("Catalog integration update triggered…");

		Optional.ofNullable(catalogEvents.getLink()).ifPresent(it -> {

			Integration integration = repository.findUniqueIntegration();

			Map<String, Object> parameters = new HashMap<>();
			parameters.put("type", "productAdded");

			integration.getCatalogUpdate()
					.ifPresent(lastUpdate -> parameters.put("since", lastUpdate.format(DateTimeFormatter.ISO_DATE_TIME)));

			URI uri = URI.create(it.expand(parameters).getHref());

			log.info("Requesting new events from {}…", uri);

			RequestEntity<Void> request = RequestEntity.get(uri) //
					.accept(MediaTypes.HAL_JSON) //
					.build();

			initializeInventory(client.exchange(request, PRODUCTS_ADDED).getBody());
		});
	}

	private void initializeInventory(Resources<Resource<ProductAdded>> resources) {

		log.info("Processing {} new events…", resources.getContent().size());

		resources.forEach(resource -> {

			Integration integration = repository.apply(() -> initInventory(resource),
					it -> it.withCatalogUpdate(resource.getContent().getPublicationDate()));

			log.info("Successful catalog update. New reference time: {}.",
					integration.getCatalogUpdate().map(it -> it.format(DateTimeFormatter.ISO_DATE_TIME)) //
							.orElseThrow(() -> new IllegalStateException()));
		});
	}

	private void initInventory(Resource<ProductAdded> resource) {

		ProductId productId = ProductId.of(resource.getLink("product").getHref());

		log.info("Creating inventory item for product {}.", resource.getContent().getProduct().getDescription());

		inventory.findByProductId(productId) //
				.orElseGet(() -> inventory.save(InventoryItem.of(productId, 0)));
	}

	@Data
	public static class ProductAdded {

		Product product;
		LocalDateTime publicationDate;

		@Data
		public static class Product {

			String description;
			BigDecimal price;
		}
	}
}
