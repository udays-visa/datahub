package io.datahubproject.openapi.v3.controller;

import static com.linkedin.metadata.aspect.validation.ConditionalWriteValidator.HTTP_HEADER_IF_VERSION_MATCH;
import static com.linkedin.metadata.authorization.ApiOperation.READ;

import com.datahub.authentication.Actor;
import com.datahub.authentication.Authentication;
import com.datahub.authentication.AuthenticationContext;
import com.datahub.authorization.AuthUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.ByteString;
import com.linkedin.entity.EnvelopedAspect;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.aspect.AspectRetriever;
import com.linkedin.metadata.aspect.batch.AspectsBatch;
import com.linkedin.metadata.aspect.batch.BatchItem;
import com.linkedin.metadata.aspect.batch.ChangeMCP;
import com.linkedin.metadata.entity.EntityApiUtils;
import com.linkedin.metadata.entity.IngestResult;
import com.linkedin.metadata.entity.UpdateAspectResult;
import com.linkedin.metadata.entity.ebean.batch.AspectsBatchImpl;
import com.linkedin.metadata.entity.ebean.batch.ChangeItemImpl;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.query.filter.SortCriterion;
import com.linkedin.metadata.query.filter.SortOrder;
import com.linkedin.metadata.search.ScrollResult;
import com.linkedin.metadata.search.SearchEntity;
import com.linkedin.metadata.search.SearchEntityArray;
import com.linkedin.metadata.utils.AuditStampUtils;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.metadata.utils.SearchUtil;
import com.linkedin.mxe.SystemMetadata;
import io.datahubproject.metadata.context.OperationContext;
import io.datahubproject.metadata.context.RequestContext;
import io.datahubproject.openapi.controller.GenericEntitiesController;
import io.datahubproject.openapi.exception.InvalidUrnException;
import io.datahubproject.openapi.exception.UnauthorizedException;
import io.datahubproject.openapi.v3.models.AspectItem;
import io.datahubproject.openapi.v3.models.GenericAspectV3;
import io.datahubproject.openapi.v3.models.GenericEntityAspectsBodyV3;
import io.datahubproject.openapi.v3.models.GenericEntityScrollResultV3;
import io.datahubproject.openapi.v3.models.GenericEntityV3;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController("EntityControllerV3")
@RequiredArgsConstructor
@RequestMapping("/v3/entity")
@Slf4j
@Hidden
public class EntityController
    extends GenericEntitiesController<
        GenericAspectV3, GenericEntityV3, GenericEntityScrollResultV3> {

  @Tag(name = "Generic Entities")
  @PostMapping(value = "/{entityName}/batchGet", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Get a batch of entities")
  public ResponseEntity<List<GenericEntityV3>> getEntityBatch(
      HttpServletRequest httpServletRequest,
      @RequestParam(value = "systemMetadata", required = false, defaultValue = "false")
          Boolean withSystemMetadata,
      @RequestBody @Nonnull String jsonEntityList)
      throws URISyntaxException, JsonProcessingException {

    LinkedHashMap<Urn, Map<String, Long>> requestMap = toEntityVersionRequest(jsonEntityList);

    Authentication authentication = AuthenticationContext.getAuthentication();
    OperationContext opContext =
        OperationContext.asSession(
            systemOperationContext,
            RequestContext.builder()
                .buildOpenapi(
                    authentication.getActor().toUrnStr(),
                    httpServletRequest,
                    "getEntityBatch",
                    requestMap.keySet().stream()
                        .map(Urn::getEntityType)
                        .collect(Collectors.toSet())),
            authorizationChain,
            authentication,
            true);

    if (!AuthUtil.isAPIAuthorizedEntityUrns(opContext, READ, requestMap.keySet())) {
      throw new UnauthorizedException(
          authentication.getActor().toUrnStr() + " is unauthorized to " + READ + "  entities.");
    }

    return ResponseEntity.of(
        Optional.of(
            buildEntityVersionedAspectList(
                opContext, requestMap.keySet(), requestMap, withSystemMetadata, true)));
  }

  @Tag(name = "Generic Entities", description = "API for interacting with generic entities.")
  @PostMapping(value = "/scroll", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "Scroll entities")
  public ResponseEntity<GenericEntityScrollResultV3> scrollEntities(
      HttpServletRequest request,
      @RequestParam(value = "count", defaultValue = "10") Integer count,
      @RequestParam(value = "query", defaultValue = "*") String query,
      @RequestParam(value = "scrollId", required = false) String scrollId,
      @RequestParam(value = "sort", required = false, defaultValue = "urn") String sortField,
      @RequestParam(value = "sortCriteria", required = false) List<String> sortFields,
      @RequestParam(value = "sortOrder", required = false, defaultValue = "ASCENDING")
          String sortOrder,
      @RequestParam(value = "systemMetadata", required = false, defaultValue = "false")
          Boolean withSystemMetadata,
      @RequestParam(value = "skipCache", required = false, defaultValue = "false")
          Boolean skipCache,
      @RequestParam(value = "includeSoftDelete", required = false, defaultValue = "false")
          Boolean includeSoftDelete,
      @RequestBody @Nonnull GenericEntityAspectsBodyV3 entityAspectsBody)
      throws URISyntaxException {

    final Collection<String> resolvedEntityNames;
    if (entityAspectsBody.getEntities() != null) {
      resolvedEntityNames =
          entityAspectsBody.getEntities().stream()
              .map(entityName -> entityRegistry.getEntitySpec(entityName))
              .map(EntitySpec::getName)
              .toList();
    } else {
      resolvedEntityNames =
          entityRegistry.getEntitySpecs().values().stream().map(EntitySpec::getName).toList();
    }

    Authentication authentication = AuthenticationContext.getAuthentication();

    OperationContext opContext =
        OperationContext.asSession(
            systemOperationContext,
            RequestContext.builder()
                .buildOpenapi(
                    authentication.getActor().toUrnStr(),
                    request,
                    "scrollEntities",
                    resolvedEntityNames),
            authorizationChain,
            authentication,
            true);

    if (!AuthUtil.isAPIAuthorizedEntityType(opContext, READ, resolvedEntityNames)) {
      throw new UnauthorizedException(
          authentication.getActor().toUrnStr() + " is unauthorized to " + READ + "  entities.");
    }

    List<SortCriterion> sortCriteria;
    if (!CollectionUtils.isEmpty(sortFields)) {
      sortCriteria = new ArrayList<>();
      sortFields.forEach(
          field -> sortCriteria.add(SearchUtil.sortBy(field, SortOrder.valueOf(sortOrder))));
    } else {
      sortCriteria =
          Collections.singletonList(SearchUtil.sortBy(sortField, SortOrder.valueOf(sortOrder)));
    }

    ScrollResult result =
        searchService.scrollAcrossEntities(
            opContext
                .withSearchFlags(flags -> DEFAULT_SEARCH_FLAGS)
                .withSearchFlags(flags -> flags.setSkipCache(skipCache))
                .withSearchFlags(flags -> flags.setIncludeSoftDeleted(includeSoftDelete)),
            resolvedEntityNames,
            query,
            null,
            sortCriteria,
            scrollId,
            null,
            count);

    if (!AuthUtil.isAPIAuthorizedResult(opContext, result)) {
      throw new UnauthorizedException(
          authentication.getActor().toUrnStr() + " is unauthorized to " + READ + " entities.");
    }

    return ResponseEntity.ok(
        buildScrollResult(
            opContext,
            result.getEntities(),
            entityAspectsBody.getAspects(),
            withSystemMetadata,
            result.getScrollId(),
            entityAspectsBody.getAspects() != null));
  }

  @Override
  public GenericEntityScrollResultV3 buildScrollResult(
      @Nonnull OperationContext opContext,
      SearchEntityArray searchEntities,
      Set<String> aspectNames,
      boolean withSystemMetadata,
      @Nullable String scrollId,
      boolean expandEmpty)
      throws URISyntaxException {
    return GenericEntityScrollResultV3.builder()
        .entities(
            toRecordTemplates(
                opContext, searchEntities, aspectNames, withSystemMetadata, expandEmpty))
        .scrollId(scrollId)
        .build();
  }

  @Override
  protected List<GenericEntityV3> buildEntityVersionedAspectList(
      @Nonnull OperationContext opContext,
      Collection<Urn> requestedUrns,
      LinkedHashMap<Urn, Map<String, Long>> urnAspectVersions,
      boolean withSystemMetadata,
      boolean expandEmpty)
      throws URISyntaxException {

    if (!urnAspectVersions.isEmpty()) {
      Map<Urn, List<EnvelopedAspect>> aspects =
          entityService.getEnvelopedVersionedAspects(
              opContext, resolveAspectNames(urnAspectVersions, 0L, expandEmpty), false);

      return urnAspectVersions.keySet().stream()
          .filter(urn -> aspects.containsKey(urn) && !aspects.get(urn).isEmpty())
          .map(
              u ->
                  GenericEntityV3.builder()
                      .build(
                          objectMapper, u, toAspectItemMap(u, aspects.get(u), withSystemMetadata)))
          .collect(Collectors.toList());
    } else if (!expandEmpty) {
      return requestedUrns.stream()
          .map(u -> GenericEntityV3.builder().build(objectMapper, u, Collections.emptyMap()))
          .collect(Collectors.toList());
    }

    return List.of();
  }

  private Map<String, AspectItem> toAspectItemMap(
      Urn urn, List<EnvelopedAspect> aspects, boolean withSystemMetadata) {
    return aspects.stream()
        .map(
            a ->
                Map.entry(
                    a.getName(),
                    AspectItem.builder()
                        .aspect(toRecordTemplate(lookupAspectSpec(urn, a.getName()).get(), a))
                        .systemMetadata(withSystemMetadata ? a.getSystemMetadata() : null)
                        .auditStamp(withSystemMetadata ? a.getCreated() : null)
                        .build()))
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  protected List<GenericEntityV3> buildEntityList(
      Set<IngestResult> ingestResults, boolean withSystemMetadata) {
    List<GenericEntityV3> responseList = new LinkedList<>();

    Map<Urn, List<IngestResult>> entityMap =
        ingestResults.stream().collect(Collectors.groupingBy(IngestResult::getUrn));
    for (Map.Entry<Urn, List<IngestResult>> urnAspects : entityMap.entrySet()) {
      Map<String, AspectItem> aspectsMap =
          urnAspects.getValue().stream()
              .map(
                  ingest ->
                      Map.entry(
                          ingest.getRequest().getAspectName(),
                          AspectItem.builder()
                              .aspect(ingest.getRequest().getRecordTemplate())
                              .systemMetadata(
                                  withSystemMetadata
                                      ? ingest.getRequest().getSystemMetadata()
                                      : null)
                              .auditStamp(
                                  withSystemMetadata ? ingest.getRequest().getAuditStamp() : null)
                              .build()))
              .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
      responseList.add(
          GenericEntityV3.builder().build(objectMapper, urnAspects.getKey(), aspectsMap));
    }
    return responseList;
  }

  @Override
  protected GenericEntityV3 buildGenericEntity(
      @Nonnull String aspectName,
      @Nonnull UpdateAspectResult updateAspectResult,
      boolean withSystemMetadata) {
    return GenericEntityV3.builder()
        .build(
            objectMapper,
            updateAspectResult.getUrn(),
            Map.of(
                aspectName,
                AspectItem.builder()
                    .aspect(updateAspectResult.getNewValue())
                    .systemMetadata(
                        withSystemMetadata ? updateAspectResult.getNewSystemMetadata() : null)
                    .auditStamp(withSystemMetadata ? updateAspectResult.getAuditStamp() : null)
                    .build()));
  }

  @Override
  protected GenericEntityV3 buildGenericEntity(
      @Nonnull String aspectName, @Nonnull IngestResult ingestResult, boolean withSystemMetadata) {
    return GenericEntityV3.builder()
        .build(
            objectMapper,
            ingestResult.getUrn(),
            Map.of(
                aspectName,
                AspectItem.builder()
                    .aspect(ingestResult.getRequest().getRecordTemplate())
                    .systemMetadata(
                        withSystemMetadata ? ingestResult.getRequest().getSystemMetadata() : null)
                    .auditStamp(
                        withSystemMetadata ? ingestResult.getRequest().getAuditStamp() : null)
                    .build()));
  }

  private List<GenericEntityV3> toRecordTemplates(
      @Nonnull OperationContext opContext,
      SearchEntityArray searchEntities,
      Set<String> aspectNames,
      boolean withSystemMetadata,
      boolean expandEmpty)
      throws URISyntaxException {
    return buildEntityList(
        opContext,
        searchEntities.stream().map(SearchEntity::getEntity).collect(Collectors.toList()),
        aspectNames,
        withSystemMetadata,
        expandEmpty);
  }

  private LinkedHashMap<Urn, Map<String, Long>> toEntityVersionRequest(
      @Nonnull String entityArrayList) throws JsonProcessingException, InvalidUrnException {
    JsonNode entities = objectMapper.readTree(entityArrayList);

    LinkedHashMap<Urn, Map<String, Long>> items = new LinkedHashMap<>();
    if (entities.isArray()) {
      Iterator<JsonNode> entityItr = entities.iterator();
      while (entityItr.hasNext()) {
        JsonNode entity = entityItr.next();
        if (!entity.has("urn")) {
          throw new IllegalArgumentException("Missing `urn` field");
        }
        Urn entityUrn = validatedUrn(entity.get("urn").asText());
        items.putIfAbsent(entityUrn, new HashMap<>());

        Iterator<Map.Entry<String, JsonNode>> aspectItr = entity.fields();
        while (aspectItr.hasNext()) {
          Map.Entry<String, JsonNode> aspect = aspectItr.next();

          if ("urn".equals(aspect.getKey())) {
            continue;
          }

          AspectSpec aspectSpec = lookupAspectSpec(entityUrn, aspect.getKey()).orElse(null);

          if (aspectSpec != null) {

            Map<String, String> headers = null;
            if (aspect.getValue().has("headers")) {
              headers =
                  objectMapper.convertValue(
                      aspect.getValue().get("headers"), new TypeReference<>() {});
              items
                  .get(entityUrn)
                  .put(
                      aspectSpec.getName(),
                      Long.parseLong(headers.getOrDefault(HTTP_HEADER_IF_VERSION_MATCH, "0")));
            } else {
              items.get(entityUrn).put(aspectSpec.getName(), 0L);
            }
          }
        }

        // handle no aspects specified, default latest version
        if (items.get(entityUrn).isEmpty()) {
          for (AspectSpec aspectSpec :
              entityRegistry.getEntitySpec(entityUrn.getEntityType()).getAspectSpecs()) {
            items.get(entityUrn).put(aspectSpec.getName(), 0L);
          }
        }
      }
    }

    return items;
  }

  @Override
  protected AspectsBatch toMCPBatch(
      @Nonnull OperationContext opContext, String entityArrayList, Actor actor)
      throws JsonProcessingException, InvalidUrnException {
    JsonNode entities = objectMapper.readTree(entityArrayList);

    List<BatchItem> items = new LinkedList<>();
    if (entities.isArray()) {
      Iterator<JsonNode> entityItr = entities.iterator();
      while (entityItr.hasNext()) {
        JsonNode entity = entityItr.next();
        if (!entity.has("urn")) {
          throw new IllegalArgumentException("Missing `urn` field");
        }
        Urn entityUrn = validatedUrn(entity.get("urn").asText());

        Iterator<Map.Entry<String, JsonNode>> aspectItr = entity.fields();
        while (aspectItr.hasNext()) {
          Map.Entry<String, JsonNode> aspect = aspectItr.next();

          if ("urn".equals(aspect.getKey())) {
            continue;
          }

          AspectSpec aspectSpec = lookupAspectSpec(entityUrn, aspect.getKey()).orElse(null);

          if (aspectSpec != null) {

            SystemMetadata systemMetadata = null;
            if (aspect.getValue().has("systemMetadata")) {
              systemMetadata =
                  EntityApiUtils.parseSystemMetadata(
                      objectMapper.writeValueAsString(aspect.getValue().get("systemMetadata")));
              ((ObjectNode) aspect.getValue()).remove("systemMetadata");
            }
            Map<String, String> headers = null;
            if (aspect.getValue().has("headers")) {
              headers =
                  objectMapper.convertValue(
                      aspect.getValue().get("headers"), new TypeReference<>() {});
            }

            ChangeItemImpl.ChangeItemImplBuilder builder =
                ChangeItemImpl.builder()
                    .urn(entityUrn)
                    .aspectName(aspectSpec.getName())
                    .auditStamp(AuditStampUtils.createAuditStamp(actor.toUrnStr()))
                    .systemMetadata(systemMetadata)
                    .headers(headers)
                    .recordTemplate(
                        GenericRecordUtils.deserializeAspect(
                            ByteString.copyString(
                                objectMapper.writeValueAsString(aspect.getValue().get("value")),
                                StandardCharsets.UTF_8),
                            GenericRecordUtils.JSON,
                            aspectSpec));

            items.add(builder.build(opContext.getRetrieverContext().get().getAspectRetriever()));
          }
        }
      }
    }
    return AspectsBatchImpl.builder()
        .items(items)
        .retrieverContext(opContext.getRetrieverContext().get())
        .build();
  }

  @Override
  protected ChangeMCP toUpsertItem(
      @Nonnull AspectRetriever aspectRetriever,
      Urn entityUrn,
      AspectSpec aspectSpec,
      Boolean createIfEntityNotExists,
      Boolean createIfNotExists,
      String jsonAspect,
      Actor actor)
      throws JsonProcessingException {
    JsonNode jsonNode = objectMapper.readTree(jsonAspect);
    String aspectJson = jsonNode.get("value").toString();

    final ChangeType changeType;
    if (Boolean.TRUE.equals(createIfEntityNotExists)) {
      changeType = ChangeType.CREATE_ENTITY;
    } else if (Boolean.TRUE.equals(createIfNotExists)) {
      changeType = ChangeType.CREATE;
    } else {
      changeType = ChangeType.UPSERT;
    }

    return ChangeItemImpl.builder()
        .urn(entityUrn)
        .aspectName(aspectSpec.getName())
        .changeType(changeType)
        .auditStamp(AuditStampUtils.createAuditStamp(actor.toUrnStr()))
        .recordTemplate(
            GenericRecordUtils.deserializeAspect(
                ByteString.copyString(aspectJson, StandardCharsets.UTF_8),
                GenericRecordUtils.JSON,
                aspectSpec))
        .build(aspectRetriever);
  }
}
