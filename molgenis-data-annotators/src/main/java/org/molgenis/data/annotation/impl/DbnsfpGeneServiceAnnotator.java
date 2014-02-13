package org.molgenis.data.annotation.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.molgenis.MolgenisFieldTypes.FieldTypeEnum;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.RepositoryAnnotator;
import org.molgenis.data.support.DefaultAttributeMetaData;
import org.molgenis.data.support.DefaultEntityMetaData;
import org.springframework.stereotype.Component;

/**
 * <p>
 * This class uses a location to retrieve a gene from the ... class. This gene can then be used to approach the dbNSFP
 * gene file. It extracts relevant gene annotations from this file. This can be run seperately from the dbNSFP variant
 * service.
 * </p>
 * 
 * <p>
 * <b>dbNSFP gene returns:</b>
 * 
 * Gene_name Ensembl_gene chr Gene_old_names Gene_other_names Uniprot_acc Uniprot_id Entrez_gene_id CCDS_id Refseq_id
 * ucsc_id MIM_id Gene_full_name Pathway(Uniprot) Pathway(ConsensusPathDB) Function_description Disease_description
 * MIM_phenotype_id MIM_disease Trait_association(GWAS) GO_Slim_biological_process GO_Slim_cellular_component
 * GO_Slim_molecular_function Expression(egenetics) Expression(GNF/Atlas) Interactions(IntAct) Interactions(BioGRID)
 * Interactions(ConsensusPathDB) P(HI) P(rec) Known_rec_info Essential_gene
 * </p>
 * 
 * @author mdehaan
 * 
 * @version dbNSFP version 2.3 downloaded January 26, 2014
 * 
 * */
@Component("dbnsfpGeneService")
public class DbnsfpGeneServiceAnnotator implements RepositoryAnnotator
{
	private static final String POSITION = "pos";

	// FIXME set runtime property for file location
	private static final String GENE_FILE = "/Users/mdehaan/bin/tools/dbnsfp/dbNSFP2.3_gene";

	private static final String[] FEATURES = determineFeatures();

	@Override
	public Iterator<Entity> annotate(Iterator<Entity> source)
	{
		List<Entity> results = new ArrayList<Entity>();

		while (source.hasNext())
		{
			Entity entity = source.next();
			Integer position = entity.getInt(POSITION);

			// TODO call variant to gene class to get a gene value with which i can
			// retrieve dbnsfp gene lines

		}
		return results.iterator();
	}

	@Override
	public EntityMetaData getOutputMetaData()
	{
		DefaultEntityMetaData metadata = new DefaultEntityMetaData(this.getClass().getName());

		for (String attribute : FEATURES)
		{
			if (attribute != null)
			{
				// FIXME not all attributes are strings
				metadata.addAttributeMetaData(new DefaultAttributeMetaData(attribute, FieldTypeEnum.STRING));
			}
		}

		return metadata;
	}

	@Override
	public EntityMetaData getInputMetaData()
	{
		DefaultEntityMetaData metadata = new DefaultEntityMetaData(this.getClass().getName());

		metadata.addAttributeMetaData(new DefaultAttributeMetaData(POSITION, FieldTypeEnum.LONG));

		return metadata;
	}

	@Override
	public Boolean canAnnotate(EntityMetaData sourceMetaData)
	{
		boolean canAnnotate = true;
		Iterable<AttributeMetaData> inputAttributes = getInputMetaData().getAttributes();
		for (AttributeMetaData attribute : inputAttributes)
		{
			if (sourceMetaData.getAttribute(attribute.getName()) == null)
			{
				// all attributes from the inputmetadata must be present to annotate.
				canAnnotate = false;
			}
		}

		return canAnnotate;
	}

	@Override
	public String getName()
	{
		return "dbNSFP-Gene";
	}

	private static String[] determineFeatures()
	{
		String[] features = null;

		try
		{
			FileReader reader = new FileReader(new File(GENE_FILE));
			BufferedReader bufferedReader = new BufferedReader(reader);

			String line = bufferedReader.readLine();
			features = line.split("\t");
			bufferedReader.close();
		}
		catch (FileNotFoundException e)
		{
			throw new RuntimeException(e);
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}

		// remove the # from the first feature in the header
		features[0] = features[0].replace("#", "");

		return features;
	}

}