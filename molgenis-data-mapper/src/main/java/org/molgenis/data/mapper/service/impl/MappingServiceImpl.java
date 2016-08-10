package org.molgenis.data.mapper.service.impl;

import org.molgenis.MolgenisFieldTypes.AttributeType;
import org.molgenis.auth.MolgenisUser;
import org.molgenis.data.*;
import org.molgenis.data.mapper.mapping.model.AttributeMapping;
import org.molgenis.data.mapper.mapping.model.EntityMapping;
import org.molgenis.data.mapper.mapping.model.MappingProject;
import org.molgenis.data.mapper.mapping.model.MappingTarget;
import org.molgenis.data.mapper.repository.MappingProjectRepository;
import org.molgenis.data.mapper.service.AlgorithmService;
import org.molgenis.data.mapper.service.MappingService;
import org.molgenis.data.meta.model.AttributeMetaDataFactory;
import org.molgenis.data.meta.model.EntityMetaData;
import org.molgenis.data.support.DynamicEntity;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.security.core.runas.RunAsSystem;
import org.molgenis.security.permission.PermissionSystemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static org.molgenis.MolgenisFieldTypes.AttributeType.*;
import static org.molgenis.data.mapper.meta.MappingProjectMetaData.NAME;
import static org.molgenis.data.meta.model.EntityMetaData.AttributeCopyMode.DEEP_COPY_ATTRS;
import static org.molgenis.security.core.runas.RunAsSystemProxy.runAsSystem;
import static org.molgenis.util.DependencyResolver.hasSelfReferences;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

public class MappingServiceImpl implements MappingService
{
	private static final Logger LOG = LoggerFactory.getLogger(MappingServiceImpl.class);

	private static final int BATCH_SIZE = 1000;
	public static final String SOURCE = "source";

	private final DataService dataService;
	private final AlgorithmService algorithmService;
	private final IdGenerator idGenerator;
	private final MappingProjectRepository mappingProjectRepository;
	private final PermissionSystemService permissionSystemService;
	private final AttributeMetaDataFactory attrMetaFactory;

	@Autowired
	public MappingServiceImpl(DataService dataService, AlgorithmService algorithmService, IdGenerator idGenerator,
			MappingProjectRepository mappingProjectRepository, PermissionSystemService permissionSystemService,
			AttributeMetaDataFactory attrMetaFactory)
	{
		this.dataService = requireNonNull(dataService);
		this.algorithmService = requireNonNull(algorithmService);
		this.idGenerator = requireNonNull(idGenerator);
		this.mappingProjectRepository = requireNonNull(mappingProjectRepository);
		this.permissionSystemService = requireNonNull(permissionSystemService);
		this.attrMetaFactory = requireNonNull(attrMetaFactory);
	}

	@Override
	@RunAsSystem
	public MappingProject addMappingProject(String projectName, MolgenisUser owner, String target)
	{
		MappingProject mappingProject = new MappingProject(projectName, owner);
		mappingProject.addTarget(dataService.getEntityMetaData(target));
		mappingProjectRepository.add(mappingProject);
		return mappingProject;
	}

	@Override
	@RunAsSystem
	public void deleteMappingProject(String mappingProjectId)
	{
		mappingProjectRepository.delete(mappingProjectId);
	}

	@Override
	@PreAuthorize("hasAnyRole('ROLE_SYSTEM, ROLE_SU, ROLE_PLUGIN_WRITE_MENUMANAGER')")
	@Transactional
	public MappingProject cloneMappingProject(String mappingProjectId)
	{
		MappingProject mappingProject = mappingProjectRepository.getMappingProject(mappingProjectId);
		if (mappingProject == null)
		{
			throw new UnknownEntityException("Mapping project [" + mappingProjectId + "] does not exist");
		}
		String mappingProjectName = mappingProject.getName();

		// determine cloned mapping project name (use Windows 7 naming strategy):
		String clonedMappingProjectName;
		for (int i = 1; ; ++i)
		{
			if (i == 1)
			{
				clonedMappingProjectName = mappingProjectName + " - Copy";
			}
			else
			{
				clonedMappingProjectName = mappingProjectName + " - Copy (" + i + ")";
			}

			if (mappingProjectRepository.getMappingProjects(new QueryImpl<Entity>().eq(NAME, clonedMappingProjectName))
					.isEmpty())
			{
				break;
			}
		}

		return cloneMappingProject(mappingProject, clonedMappingProjectName);
	}

	@Override
	@PreAuthorize("hasAnyRole('ROLE_SYSTEM, ROLE_SU, ROLE_PLUGIN_WRITE_MENUMANAGER')")
	@Transactional
	public MappingProject cloneMappingProject(String mappingProjectId, String clonedMappingProjectName)
	{
		MappingProject mappingProject = mappingProjectRepository.getMappingProject(mappingProjectId);
		if (mappingProject == null)
		{
			throw new UnknownEntityException("Mapping project [" + mappingProjectId + "] does not exist");
		}

		return cloneMappingProject(mappingProject, clonedMappingProjectName);
	}

	private MappingProject cloneMappingProject(MappingProject mappingProject, String clonedMappingProjectName)
	{
		mappingProject.removeIdentifiers();
		mappingProject.setName(clonedMappingProjectName);
		mappingProjectRepository.add(mappingProject);
		return mappingProject;
	}

	@Override
	@RunAsSystem
	public List<MappingProject> getAllMappingProjects()
	{
		return mappingProjectRepository.getAllMappingProjects();
	}

	@Override
	@RunAsSystem
	public void updateMappingProject(MappingProject mappingProject)
	{
		mappingProjectRepository.update(mappingProject);
	}

	@Override
	@RunAsSystem
	public MappingProject getMappingProject(String identifier)
	{
		return mappingProjectRepository.getMappingProject(identifier);
	}

	public String applyMappings(MappingTarget mappingTarget, String entityName)
	{
		return applyMappings(mappingTarget, entityName, true);
	}

	// TODO discuss: why isn't this method transactional?
	@Override
	public String applyMappings(MappingTarget mappingTarget, String entityName, boolean addSourceAttribute)
	{
		EntityMetaData targetMetaData = EntityMetaData.newInstance(mappingTarget.getTarget(), DEEP_COPY_ATTRS);
		targetMetaData.setName(entityName);
		targetMetaData.setLabel(entityName);
		if (addSourceAttribute)
		{
			targetMetaData.addAttribute(attrMetaFactory.create().setName(SOURCE));
		}

		// add a new repository if the target repo doesn't exist, or check if the target repository is compatible with
		// the result of the mappings
		Repository<Entity> targetRepo;
		if (!dataService.hasRepository(entityName))
		{
			targetRepo = runAsSystem(() -> dataService.getMeta().addEntityMeta(targetMetaData));
			permissionSystemService.giveUserEntityPermissions(SecurityContextHolder.getContext(),
					Collections.singletonList(targetRepo.getName()));
		}
		else
		{
			// Get an existing repository
			targetRepo = dataService.getRepository(entityName);

			// If the addSourceAttribute is true, but the existing repository does not have the SOURCE attribute yet
			// Get the existing metadata and add the SOURCE attribute
			EntityMetaData existingTargetMetaData = targetRepo.getEntityMetaData();
			if (existingTargetMetaData.getAttribute(SOURCE) == null && addSourceAttribute)
			{
				existingTargetMetaData.addAttribute(attrMetaFactory.create().setName(SOURCE));
				dataService.getMeta().updateEntityMeta(existingTargetMetaData);
			}
		}

		try
		{
			LOG.info("Applying mappings to repository [" + targetMetaData.getName() + "]");
			applyMappingsToRepositories(mappingTarget, targetRepo);
			if (hasSelfReferences(targetRepo.getEntityMetaData()))
			{
				LOG.info("Self reference found, applying the mapping for a second time to set references");
				applyMappingsToRepositories(mappingTarget, targetRepo);
			}
			LOG.info("Done applying mappings to repository [" + targetMetaData.getName() + "]");
			return targetMetaData.getName();
		}
		catch (RuntimeException ex)
		{
			LOG.error("Error applying mappings, dropping created repository.", ex);
			dataService.getMeta().deleteEntityMeta(targetMetaData.getName());
			throw ex;
		}
	}

	private void applyMappingsToRepositories(MappingTarget mappingTarget, Repository<Entity> targetRepo)
	{
		for (EntityMapping sourceMapping : mappingTarget.getEntityMappings())
		{
			applyMappingToRepo(sourceMapping, targetRepo);
		}
	}

	private void applyMappingToRepo(EntityMapping sourceMapping, Repository<Entity> targetRepo)
	{
		EntityMetaData targetMetaData = targetRepo.getEntityMetaData();
		Repository<Entity> sourceRepo = dataService.getRepository(sourceMapping.getName());

		sourceRepo.iterator().forEachRemaining(sourceEntity -> {
			{
				Entity mappedEntity = applyMappingToEntity(sourceMapping, sourceEntity, targetMetaData,
						sourceMapping.getSourceEntityMetaData());
				if (targetRepo.findOneById(mappedEntity.getIdValue()) == null)
				{
					targetRepo.add(mappedEntity);
				}
				else
				{
					targetRepo.update(mappedEntity);
				}
			}
		});
	}

	private Entity applyMappingToEntity(EntityMapping sourceMapping, Entity sourceEntity, EntityMetaData targetMetaData,
			EntityMetaData sourceEntityMetaData)
	{
		Entity target = new DynamicEntity(targetMetaData);
		target.set(SOURCE, sourceMapping.getName());

		sourceMapping.getAttributeMappings().forEach(
				attributeMapping -> applyMappingToAttribute(attributeMapping, sourceEntity, target,
						sourceEntityMetaData));
		return target;
	}

	@Override
	public String generateId(AttributeType dataType, Long count)
	{
		Object id;
		if (dataType == INT || dataType == LONG || dataType == DECIMAL)
		{
			id = count + 1;
		}
		else
		{
			id = idGenerator.generateId();
		}
		return id.toString();
	}

	private void applyMappingToAttribute(AttributeMapping attributeMapping, Entity sourceEntity, Entity target,
			EntityMetaData entityMetaData)
	{
		target.set(attributeMapping.getTargetAttributeMetaData().getName(),
				algorithmService.apply(attributeMapping, sourceEntity, entityMetaData));
	}
}
