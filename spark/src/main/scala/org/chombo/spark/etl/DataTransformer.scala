/*
 * chombo-spark: etl on spark
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */


package org.chombo.spark.etl

import org.chombo.spark.common.JobConfiguration
import org.apache.spark.SparkContext
import org.chombo.util.BasicUtils
import org.chombo.transformer.TransformerFactory
import com.typesafe.config.Config
import org.chombo.util.ProcessorAttributeSchema
import org.chombo.transformer.AttributeTransformer
import scala.collection.JavaConverters._
import org.chombo.util.ProcessorAttribute
import org.chombo.transformer.ContextAwareTransformer
import org.chombo.spark.common.GeneralUtility
import java.io.InputStream
import org.chombo.util.Utility


/**
 * @author pranab
 *
 */
abstract trait TransformerRegistration {
  def registerTransformers(fieldOrd : Int, transformers : AttributeTransformer*) 
  def registerGenerators(generators : AttributeTransformer*)   
}

/**
 * @author pranab
 *
 */
abstract trait TransformerBuilder {
	def createTransformers(config : Config, transformer : TransformerRegistration) 
}


/**
 * @author pranab
 *
 */
object DataTransformer extends JobConfiguration with TransformerRegistration  with GeneralUtility {
    val mutTransformers : scala.collection.mutable.HashMap[Int, Array[AttributeTransformer]]   = scala.collection.mutable.HashMap()
    lazy val transformers :  Map[Int, Array[AttributeTransformer]]   = mutTransformers.toMap
    var mutGenerators : Array[Option[AttributeTransformer]] = _
    lazy val generators : Array[AttributeTransformer] = 
    	if (null != mutGenerators ) filterNoneFromArray(mutGenerators)
    	else Array[AttributeTransformer]()
    val context : java.util.Map[String, Object] = new java.util.HashMap[String, Object]()
    
    /**
    * @param args
    * @return
    */
    def main(args: Array[String]) {
       val appName = "dataTransformer"
	   val Array(inputPath: String, outputPath: String, configFile: String) = getCommandLineArgs(args, 3)
	   val config = createConfig(configFile)
	   val sparkConf = createSparkConf(appName, config, false)
	   val sparkCntxt = new SparkContext(sparkConf)
	   val appConfig = config.getConfig(appName)
	   
	  
	   
	   //configurations
	   val fieldDelimIn = getStringParamOrElse(appConfig, "field.delim.in", ",")
	   val fieldDelimOut = getStringParamOrElse(appConfig, "field.delim.out", ",")
	   val transformerSchema = BasicUtils.getProcessingSchema(
	       getMandatoryStringParam(appConfig, "schema.filePath", "missing schema file path configuration"))
	   transformerSchema.validateTargetAttributeMapping()
	   val customClass = if (appConfig.hasPath("custom.transFactoryClass"))
	     appConfig.getString("custom.trans.factory.class")
	   else
	     null
	   TransformerFactory.initialize(customClass, appConfig)
	   val ordinals  = transformerSchema.getAttributeOrdinals()
	   val debugOn = appConfig.getBoolean("debug.on")
	   val saveOutput = appConfig.getBoolean("save.output")
	   
	   //create trassformers and generator
	   var foundinConfig = createTransformersFromConfig(transformerSchema, appConfig)
	   var foundinSchema = false
	   if (debugOn)
	     println("foundinConfig: " + foundinConfig)
	   if (foundinConfig) {
	       //found transformers in configuration, so expect generator also there
	       createGeneratorsFromConfig(transformerSchema, appConfig)
	   } else {
	     //since not in config, use schema
	     foundinSchema = createTransformersFromSchema(transformerSchema, appConfig)
	     if (debugOn)
	       println("foundinSchema: " + foundinSchema)
	     if (foundinSchema) {
	    	 createGeneratorsFromSchema(transformerSchema, appConfig)
	     }
	   }
       
       //last option is custom 
       if (!foundinConfig && !foundinSchema) {
         val bulderClassName = getMandatoryStringParam(appConfig, "transformerBuilderClass", 
             "missing transformer builder class name")
         val obj = Class.forName(bulderClassName).newInstance()
         val builder = obj.asInstanceOf[TransformerBuilder]
         builder.createTransformers(appConfig, this)
       }
       
       
       //broadcast transformers and generators
	   val brTransformers = sparkCntxt.broadcast(transformers)
	   val brGenerators = sparkCntxt.broadcast(generators)

	   //apply transformers or generators to each field in each line 
	   val data = sparkCntxt.textFile(inputPath)
	   val transformedData = data.map(line => {
	     val items = BasicUtils.getTrimmedFields(line, fieldDelimIn)
         val itemsOut = new Array[String](transformerSchema.findDerivedAttributeCount());
	     
	     getTranformedAttributes(transformerSchema,  true, items, itemsOut,brTransformers.value, brGenerators.value)
	     if (brGenerators.value.length > 0) {
	    	 getTranformedAttributes(transformerSchema,  false, items, itemsOut, brTransformers.value, brGenerators.value)
	     }
	     
	     itemsOut.mkString(fieldDelimOut)
	   })
	   
	   if (debugOn) {
	     val records = transformedData.collect.slice(0, 20)
         records.foreach(r => println(r))
	   }
	   
       if(saveOutput) {	   
		   transformedData.saveAsTextFile(outputPath) 
   	   }

    }
    
    /**
     * @param sparkCntxt
     * @param config
     * @param fromList
     * @param paramName
     */
    private def getTranformedAttributes(transformerSchema : ProcessorAttributeSchema,  isTransformer : Boolean,
        items : Array[String], itemsOut : Array[String], transformers : Map[Int, Array[AttributeTransformer]],
        generators : Array[AttributeTransformer]) {     
        //println("operation transformer " + isTransformer)
    	//for all attributes
    	transformerSchema.getAttributes().asScala.toList.foreach(prAttr => {
	       //target attributes
	       val targetfieldOrdinals = prAttr.getTargetFieldOrdinals()
	       val targetOrdsOpt = asOption(targetfieldOrdinals)
	       val attr = prAttr.getOrdinal()
	       targetOrdsOpt match {
	         case Some(targetOrds : Array[Int]) => {
	        	 	var  source:String = ""
	                val transformerList = 
	                if (isTransformer) {
	                	val fieldOrd = prAttr.getOrdinal();
	                	source = items(fieldOrd);
	                	transformers.get(fieldOrd);
	                } else {
	                	source = null;
	                	Some(generators)
	                }
	        	 	
	        	 	var transformedValues : Array[String] = new Array[String](1)
	        	 	transformerList match {
	        	 	  case Some(transformers : Array[AttributeTransformer]) => {
	        	 	    //apply all transformers
	        	 	    transformers.zipWithIndex.foreach(ztrans => {
	        	 	      setTransformerContext(ztrans._1, items, context)
	        	 	      transformedValues = ztrans._1.tranform(source)
	        	 	      //println("num trans fields " + transformedValues.length + " for attr " + attr)
	        	 	      if (ztrans._2 < transformers.length -1 && transformedValues.length > 1) {
	        	 	        throw new  IllegalStateException(
	        	 	            "for cascaded transformers only last transformer is allowed to emit multiple values")
	        	 	      }
	        	 	      source = transformedValues(0)
	        	 	    })
	        	 	  }
	        	 	  case None => {
	        	 	    //no transformers, retain source
	        	 	    transformedValues(0) = source
	        	 	  }
	        	 	}
	        	 	
	        	 	
	        	    //check output size with target attribute count
            	    if (transformedValues.length != targetfieldOrdinals.length) {
            	    	throw new IllegalStateException("transformed output size does not match with target attribute size expected " + 
            	    	    targetfieldOrdinals.length + " found " + transformedValues.length + " for field " + attr);
            	    }
	        	 	
	        	 	//populated target attributes
	        	 	targetfieldOrdinals.zipWithIndex.foreach(tf => {
	        	 	  itemsOut(tf._1) = transformedValues(tf._2)
	        	 	})
	         }
	         //no target field, discard source
	         case None =>
	       }
	       
	     })
      
    }
   
    /**
     * @param config
     * @param paramName
     * @param defValue
     * @param errorMsg
     * @return
     */
    def setTransformerContext(trans : AttributeTransformer, items : Array[String], context : 
        java.util.Map[String, Object] ) {
      trans match {
        case (cntxTrans : ContextAwareTransformer) => {
          context.clear()
          context.put("record", items)
          cntxTrans.setContext(context)
        }
        
        case _ =>
      }
    }
    
    /**
	* @param transformerSchema
	* @param config
	* @return
	*/    
    private def createTransformersFromConfig(transformerSchema : ProcessorAttributeSchema, appConfig: Config) : 
       Boolean = {
	   var foundinConfig = false
	   val ordinals  = transformerSchema.getAttributeOrdinals()
	   ordinals.foreach(ord => {
		   val  key = "transformer." + ord
		   val transformerTagList = getOptionalStringListParam(appConfig, key)
		   transformerTagList match {
		     case Some(li:java.util.List[String]) => {
		    	 val transTags = BasicUtils.fromListToStringArray(li)
		    	 createTransformers(transformerSchema, appConfig, transTags,  ord)
		    	 foundinConfig = true
		     }
		     case None =>
		   }  
	   })
      foundinConfig
    }

    /**
	* @param transformerSchema
	* @param config
	* @return
	*/    
    private def createGeneratorsFromConfig(transformerSchema : ProcessorAttributeSchema, appConfig: Config) : 
      Boolean =  {
	  var foundinSchema = false 
      val ordinals  = transformerSchema.getAttributeOrdinals()
	   mutGenerators = ordinals.map(ord => {
	       val  key = "generator." + ord
		   val generatorTag = getOptionalStringParam(appConfig, key)
		   generatorTag match {
		       case Some(genTag:String) => {
		    	   	foundinSchema = true
			   	  	Some(createGenerator(transformerSchema, appConfig, genTag,  ord))
			   }
			   case None => None
		   }  
	  })
      foundinSchema
    }   
    
    /**
	* @param transformerSchema
	* @param config
	* @return
	*/    
    private def createTransformersFromSchema(transformerSchema : ProcessorAttributeSchema, appConfig: Config) : 
      Boolean = {
       var foundinSchema = false 
    	transformerSchema.getAttributes().asScala.toList.foreach(prAttr => {
    	  val fieldOrd = prAttr.getOrdinal()
    	  val transformerTagList = prAttr.getTransformers()
    	  if (null != transformerTagList) {
    	    val transTags =  transformerTagList.asScala.toArray
    	    createTransformers(transformerSchema, appConfig, transTags,  prAttr.getOrdinal())
    	    foundinSchema = true
    	    println("found transformers for " + fieldOrd)
    	  }
    	})
    	foundinSchema
    }
    
    /**
	* @param transformerSchema
	* @param config
	* @return
	*/    
    private def createGeneratorsFromSchema(transformerSchema : ProcessorAttributeSchema, appConfig: Config) {
        val generators = transformerSchema.getAttributeGenerators()
        if (null != generators) {
	    	mutGenerators = transformerSchema.getAttributeGenerators().asScala.toArray.map(prAttr => {
	    	  val fieldOrd = prAttr.getOrdinal()
	    	  val generatorTagList = prAttr.getTransformers()
	    	  val generatorTag = if (null != generatorTagList) {
	    	    val generatorTags =  generatorTagList.asScala.toArray
	    	    Some(generatorTags(0))
	    	  } else {
	    	    None
	    	  }
	    	  generatorTag match {
			  	 case Some(genTag:String) => {
				   	  Some(createGenerator(transformerSchema, appConfig, genTag,  fieldOrd))
				 }
				 case None => None
			  }  
	  
	    	})
        }
    }     
    
    /**
	* @param transformerSchema
	* @param config
	* @param transTags
	* @param ord
	* @return
	*/    
    private  def createTransformers(transformerSchema : ProcessorAttributeSchema, config : Config , 
       transTags : Array[String],   ord : Int) {
        val prAttr = transformerSchema.findAttributeByOrdinal(ord)
         val transArr = transTags.map(tag => {
           //check if side data is needed
           val tranConfig = TransformerFactory.getTransformerConfig(config , tag, prAttr)
           var  inStrm:InputStream  = null;
		   if (tranConfig.hasPath("hdfsDataPath")) {
				inStrm = Utility.getFileStream(tranConfig.getString("hdfsDataPath"));
		   } else if (tranConfig.hasPath("fsDataPath")) {
				inStrm = BasicUtils.getFileStream(tranConfig.getString("fsDataPath"));
		   }
           val fiStrm = asOption[InputStream](inStrm)
           fiStrm match {
             case Some(strm) => {
               println("creating transformer with side data for " + tag)
               TransformerFactory.createTransformer(tag, prAttr, config, strm) 
             }
             case None => {
               println("creating transformer for " + tag)
               TransformerFactory.createTransformer(tag, prAttr, config)
             }
           }
        })
        //add transformers to map
	    mutTransformers += ord -> transArr
   }
    
    

    /**
	* @param appName
	* @param config
	* @param includeAppConfig
	* @return
	*/    
    private  def createGenerator(transformerSchema : ProcessorAttributeSchema, config : Config , 
       genTag : String, ord : Int) : AttributeTransformer = {
         val prAttr = transformerSchema.findAttributeByOrdinal(ord)
         TransformerFactory.createTransformer(genTag, prAttr, config)
   }
    
    /**
     * @param fieldOrd
     * @param transformers
     */
    def registerTransformers(fieldOrd : Int, transformers : AttributeTransformer*) {
      val transArr = transformers.toArray
      mutTransformers += fieldOrd -> transArr
    }

    /**
    * @param generators
    */
    def registerGenerators(generators : AttributeTransformer*) {
       val genArr = generators.toArray
       mutGenerators = genArr.map(gen => Some(gen))
     }   
}