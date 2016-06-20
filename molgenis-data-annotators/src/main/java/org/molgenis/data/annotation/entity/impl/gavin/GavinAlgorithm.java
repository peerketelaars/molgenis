package org.molgenis.data.annotation.entity.impl.gavin;

import org.molgenis.data.Entity;
import org.molgenis.data.annotation.entity.impl.gavin.Judgment.Method;
import org.molgenis.data.annotation.entity.impl.gavin.GavinEntry.Category;
import org.molgenis.data.annotation.entity.impl.snpEff.Impact;
import org.molgenis.data.annotation.entity.impl.snpEff.SnpEffRunner;

import java.util.HashMap;

public class GavinAlgorithm
{
	public static final String NAME = "GavinAnnotator";
	public static final String RESOURCE = "gavin";
	public static final String RESOURCE_ENTITY_NAME = "ccgg";

	public static final String PATHOMAFTHRESHOLD = "PathoMAFThreshold";
	public static final String MEANPATHOGENICCADDSCORE = "MeanPathogenicCADDScore";
	public static final String MEANPOPULATIONCADDSCORE = "MeanPopulationCADDScore";
	public static final String SPEC95THPERCADDTHRESHOLD = "Spec95thPerCADDThreshold";
	public static final String SENS95THPERCADDTHRESHOLD = "Sens95thPerCADDThreshold";
	private static final String CATEGORY = "Category";

	public static final String CLASSIFICATION = "Classification";
	public static final String CONFIDENCE = "Confidence";
	public static final String REASON = "Reason";
	public static final String VARIANT_ENTITY = "Variant";
	public static final double GENOMEWIDE_MAF_THRESHOLD = 0.00474;
	public static final int GENOMEWIDE_CADD_THRESHOLD = 15;

	/**
	 *
	 * @param impact
	 * @param caddScaled
	 * @param exacMAF
	 * @param gene
	 * @param annotationSourceEntity
	 * @return
	 */
	public Judgment classifyVariant(Impact impact, Double caddScaled, Double exacMAF,
									String gene, Entity annotationSourceEntity, HashMap<String, GavinEntry> geneToEntry)
	{
		Double pathoMAFThreshold, meanPathogenicCADDScore, meanPopulationCADDScore, spec95thPerCADDThreshold, sens95thPerCADDThreshold = null;
		Category category = null;

		//at 5, GAVIN is 92% sensitive and 78% specific, whereas at 1, GAVIN is 89% sensitive and 83% specific. However sensitivity is more important. (don't want to miss anything)
		int extraSensitivityFactor = 5;

		if(geneToEntry == null)
		{
			//get data from entity
			pathoMAFThreshold = annotationSourceEntity.getDouble(PATHOMAFTHRESHOLD) * extraSensitivityFactor * 2;
			meanPathogenicCADDScore = annotationSourceEntity.getDouble(MEANPATHOGENICCADDSCORE) - extraSensitivityFactor;
			meanPopulationCADDScore = annotationSourceEntity.getDouble(MEANPATHOGENICCADDSCORE) - extraSensitivityFactor;
			spec95thPerCADDThreshold = annotationSourceEntity.getDouble(SPEC95THPERCADDTHRESHOLD) - extraSensitivityFactor;
			sens95thPerCADDThreshold = annotationSourceEntity.getDouble(SPEC95THPERCADDTHRESHOLD) - extraSensitivityFactor;
			category = Category.valueOf(annotationSourceEntity.getString(CATEGORY));
		}
		else
		{
			//get data from map
			if(!geneToEntry.containsKey(gene))
			{
				//if we have no data for this gene, immediately fall back to the genomewide method
				return genomewideClassifyVariant(impact, caddScaled, exacMAF, gene);
			}
			else
			{
				pathoMAFThreshold = geneToEntry.get(gene).PathoMAFThreshold != null ? geneToEntry.get(gene).PathoMAFThreshold * extraSensitivityFactor * 2 : null;
				meanPathogenicCADDScore = geneToEntry.get(gene).MeanPathogenicCADDScore != null ? geneToEntry.get(gene).MeanPathogenicCADDScore - extraSensitivityFactor : null;
				meanPopulationCADDScore = geneToEntry.get(gene).MeanPopulationCADDScore != null ? geneToEntry.get(gene).MeanPopulationCADDScore - extraSensitivityFactor : null;
				spec95thPerCADDThreshold = geneToEntry.get(gene).Spec95thPerCADDThreshold != null ? geneToEntry.get(gene).Spec95thPerCADDThreshold - extraSensitivityFactor : null;
				sens95thPerCADDThreshold = geneToEntry.get(gene).Sens95thPerCADDThreshold != null ? geneToEntry.get(gene).Sens95thPerCADDThreshold - extraSensitivityFactor : null;
				category = geneToEntry.get(gene).category;
			}
		}

		String mafReason = "the variant MAF of " + exacMAF + " is less than a MAF of "+ pathoMAFThreshold + ".";

		// CADD score based classification, calibrated
		if(caddScaled != null)
		{
			if((category.equals(Category.C1) || category.equals(Category.C2)))
			{
				if(caddScaled > meanPathogenicCADDScore)
				{
					return new Judgment(Judgment.Classification.Pathogenic,  Method.calibrated, gene, "Variant CADD score of " + caddScaled + " is greater than "+meanPathogenicCADDScore+" in a gene for which CADD scores are informative. Also, " + mafReason);
				}
				else if(caddScaled < meanPopulationCADDScore)
				{
					return new Judgment(Judgment.Classification.Benign,  Method.calibrated, gene, "Variant CADD score of " + caddScaled + " is less than "+meanPopulationCADDScore+" in a gene for which CADD scores are informative, although " + mafReason);
				}
			}
			else if((category.equals(Category.C3) || category.equals(Category.C4) || category.equals(Category.C5)))
			{
				if(caddScaled > spec95thPerCADDThreshold)
				{
					return new Judgment(Judgment.Classification.Pathogenic,  Method.calibrated, gene, "Variant CADD score of " + caddScaled + " is greater than "+spec95thPerCADDThreshold+" for this gene. Also, " + mafReason);
				}
				else if(caddScaled < sens95thPerCADDThreshold)
				{
					return new Judgment(Judgment.Classification.Benign,  Method.calibrated, gene, "Variant CADD score of " + caddScaled + " is less than "+sens95thPerCADDThreshold+" for this gene, although " + mafReason);
				}
			}
		}

		// MAF based classification, calibrated but slightly relaxed with a factor x
		if(pathoMAFThreshold != null && exacMAF > pathoMAFThreshold)
		{
			return new Judgment(Judgment.Classification.Benign, Method.calibrated, gene, "Variant MAF of " + exacMAF + " is greater than "+pathoMAFThreshold+".");
		}

		// Impact based classification, calibrated
		if(impact != null)
		{
			if(category.equals(Category.I1) && impact.equals(Impact.HIGH))
			{
				return new Judgment(Judgment.Classification.Pathogenic,  Method.calibrated, gene, "Variant is of high impact, while there are no known high impact variants in the population. Also, " + mafReason);
			}
			else if(category.equals(Category.I2) && (impact.equals(Impact.MODERATE) || impact.equals(Impact.HIGH)))
			{
				return new Judgment(Judgment.Classification.Pathogenic,  Method.calibrated, gene, "Variant is of high/moderate impact, while there are no known high/moderate impact variants in the population. Also, " + mafReason);
			}
			else if(category.equals(Category.I3) && (impact.equals(Impact.LOW) || impact.equals(Impact.MODERATE) || impact.equals(Impact.HIGH)))
			{
				return new Judgment(Judgment.Classification.Pathogenic,  Method.calibrated, gene, "Variant is of high/moderate/low impact, while there are no known high/moderate/low impact variants in the population. Also, " + mafReason);
			}
			else if(impact.equals(Impact.MODIFIER))
			{
				return new Judgment(Judgment.Classification.Benign,  Method.calibrated, gene, "Variant is of 'modifier' impact, and therefore unlikely to be pathogenic. However, " + mafReason);
			}
		}

		//if everything so far has failed, we can still fall back to the genome-wide method
		return genomewideClassifyVariant(impact, caddScaled, exacMAF, gene);
	}

	/**
	 * @param impact
	 * @param caddScaled
	 * @param exacMAF
	 * @param gene
	 * @return
	 */
	public Judgment genomewideClassifyVariant(Impact impact, Double caddScaled, Double exacMAF,
			String gene)
	{

		exacMAF = exacMAF != null ? exacMAF : 0;

		if(exacMAF > GENOMEWIDE_MAF_THRESHOLD)
		{
			return new Judgment(Judgment.Classification.Benign, Method.genomewide, gene, "Variant MAF of "+exacMAF+" is not rare enough to generally be considered pathogenic.");
		}
		if(impact.equals(Impact.MODIFIER))
		{
			return new Judgment(Judgment.Classification.Benign, Method.genomewide, gene, "Variant is of 'modifier' impact, and therefore unlikely to be pathogenic.");
		}
		else
		{
			if(caddScaled != null && caddScaled > GENOMEWIDE_CADD_THRESHOLD)
			{
				return new Judgment(Judgment.Classification.Pathogenic, Method.genomewide, gene, "Variant MAF of "+exacMAF+" is rare enough to be potentially pathogenic and the CADDscore of "+caddScaled+ " is greater than a global threshold of "+GENOMEWIDE_CADD_THRESHOLD+".");
			}
			else if(caddScaled != null && caddScaled <= GENOMEWIDE_CADD_THRESHOLD)
			{
				return new Judgment(Judgment.Classification.Benign, Method.genomewide, gene, "Variant CADD score of "+caddScaled+ " is less than a global threshold of "+GENOMEWIDE_CADD_THRESHOLD+", although the variant MAF of "+exacMAF+" is rare enough to be potentially pathogenic.");
			}
			else
			{
				return new Judgment(Judgment.Classification.VOUS, Method.genomewide, gene, "Unable to classify variant as benign or pathogenic. The combination of "+impact+" impact, a CADD score "+caddScaled +" and MAF of " + exacMAF + " in " + gene + " is inconclusive.");
			}
		}
	}
}