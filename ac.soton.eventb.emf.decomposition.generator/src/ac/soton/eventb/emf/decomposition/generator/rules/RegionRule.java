package ac.soton.eventb.emf.decomposition.generator.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eventb.core.IMachineRoot;
import org.eventb.emf.core.AbstractExtension;
import org.eventb.emf.core.Attribute;
import org.eventb.emf.core.CorePackage;
import org.eventb.emf.core.EventBElement;
import org.eventb.emf.core.Project;
import org.eventb.emf.core.machine.Event;
import org.eventb.emf.core.machine.Machine;
import org.eventb.emf.core.machine.MachinePackage;
import org.eventb.emf.persistence.EventBEMFUtils;

import ac.soton.emf.translator.TranslationDescriptor;
import ac.soton.emf.translator.configuration.IRule;
import ac.soton.emf.translator.utils.Find;
import ac.soton.eventb.decomposition.AbstractRegion;
import ac.soton.eventb.emf.core.extension.navigator.refiner.AbstractElementRefiner;
import ac.soton.eventb.emf.core.extension.navigator.refiner.ElementRefinerRegistry;
import ac.soton.eventb.emf.decomposition.generator.Activator;
import ac.soton.eventb.emf.decomposition.generator.Make;
import ac.soton.eventb.emf.inclusion.EventSynchronisation;
import ac.soton.eventb.emf.inclusion.InclusionFactory;
import ac.soton.eventb.emf.inclusion.MachineInclusion;

/**
 * Rule for level 1 regions... ready or not.
 * 
 * If ready, these are the decomposed sub-regions and become the root of the region model in the 
 * sub-machine that they define. If not ready they are contained in the composed machine
 * <p>
 * This rule translates regions into machines that represent sub components
 * Currently the region must be contained in a root region that is itself contained in a machine
 * (the root region represents the current (source) machine)
 * Currently we only decompose one level at a time (even if lower levels are marked as ready)
 * </p>
 * 
 * @author cfs
 * @version
 * @see
 * @since
 */
public class RegionRule extends AbstractRegionRule implements IRule {
	
	/**
	 * the region must be contained in a root region
	 */
	@Override
	public boolean enabled(final EObject sourceElement) throws Exception  {
		if (sourceElement.eContainer() instanceof AbstractRegion
			&& sourceElement.eContainer().eContainer() instanceof Machine 
			//&& ((AbstractRegion)sourceElement).isReady()
			)
			return true;
		else
			return false;
	}

	/**
	 * the composition machine must be produced first
	 */
	@Override
	public boolean dependenciesOK(EObject sourceElement,List<TranslationDescriptor> translatedElements){
		String composedMachineName = ((AbstractRegion)sourceElement.eContainer()).getMachineName()+Activator.compositionMachinePostfix;
		compositionMachine = (Machine) Find.translatedElement(translatedElements, null, components, machine, composedMachineName);
		return compositionMachine!=null;
		
	}
	
	
	@Override
	public List<TranslationDescriptor> fire(EObject sourceElement, List<TranslationDescriptor> translatedElements) throws Exception {
		List<TranslationDescriptor> ret = new ArrayList<TranslationDescriptor>();
		AbstractRegion region = (AbstractRegion)sourceElement;
		String projectName = region.getProjectName();
		Machine sourceMachine = (Machine) region.getContaining(MachinePackage.Literals.MACHINE);
		IMachineRoot sourceMachineRoot = EventBEMFUtils.getRoot(sourceMachine);
		
		if (region.isReady()){

			Machine decomposedMachine = Make.machine(region.getMachineName(), "generated by decomposition from region: "+region.getMachineName());

			if (projectName != null && projectName.length()>0){
				Project project = Make.project(region.getProjectName(), "generated by decomposition from region: "+region.getMachineName());
				//FIXME: add support for project descriptor in translator (currently all machines go in same project as source)
				ret.add(Make.descriptor(null, null , project, 1));
				project.getComponents().add(decomposedMachine);
			}else{
				ret.add(Make.descriptor(findProject(region), components , decomposedMachine, 1));
			}
			//TODO: be more specific about the contexts that are needed
			for (String seesName : sourceMachine.getSeesNames()){
				decomposedMachine.getSeesNames().add(seesName);
			}
			
			processAllocation(region, sourceMachine, decomposedMachine, sourceMachineRoot);
	
			//update all generator IDs to the new root of the Extension
			Map<String,String> gIDMap = new HashMap<String,String>();
			for (AbstractExtension ae : region.getAllocatedExtensions()){
				AbstractExtension rootAe = getRootExtension(ae);
				if (rootAe != null){
					gIDMap.put(rootAe.getExtensionId(), ae.getExtensionId());
				}
			}
			for (EObject eObject : decomposedMachine.getAllContained(CorePackage.Literals.EVENT_BOBJECT, true)){
				if (eObject instanceof EventBElement && ((EventBElement)eObject).isLocalGenerated()) {
						Attribute gIDAtt = ((EventBElement)eObject).getAttributes().get(Activator.generatorIDKey);
						if (gIDAtt != null && gIDMap.containsKey(gIDAtt.getValue())){
							gIDAtt.setValue(gIDMap.get(gIDAtt.getValue()));
						};
				}
			}
			
			//get any allocated extensions
			EList<AbstractExtension> extensions = region.getAllocatedExtensions();
			
			// also promote any nested extensions (including nested regions)
			extensions.addAll(region.getExtensions());
			
			//add clones of all the extensions
			for (AbstractExtension ext : extensions){
				AbstractElementRefiner refiner = ElementRefinerRegistry.getRegistry().getRefiner(ext);
				if (refiner!=null) {
					Map<EObject,EObject> copy = refiner.cloneAndExtractFromRefinementChain(ext, decomposedMachine,null);
					AbstractExtension clone = (AbstractExtension) copy.get(ext);
					decomposedMachine.getExtensions().add(clone);
				}
			}
			
			//add machine includes to composition machine
			MachineInclusion inclusion = InclusionFactory.eINSTANCE.createMachineInclusion();
			inclusion.setAbstractMachine(decomposedMachine);
			inclusion.getPrefixes().add(region.getMachineName());
			compositionMachine.getExtensions().add(inclusion);
			
			for (Event event : decomposedMachine.getEvents()){
				EventSynchronisation synch = InclusionFactory.eINSTANCE.createEventSynchronisation();
				synch.setSynchronisedEvent(event);
				synch.setPrefix(region.getMachineName());
				//compositionMachine
				Event compositionEvent = (Event)Find.element(compositionMachine, compositionMachine, MachinePackage.Literals.MACHINE__EVENTS, MachinePackage.Literals.EVENT, event.getName());
				if (compositionEvent != null){
					compositionEvent.getExtensions().add(synch);
				}
			}
			
		}else{
			//when region is not ready its allocation is added to the compositionMachine
			processAllocation(region, sourceMachine, compositionMachine, sourceMachineRoot);
		}

		return ret;
	}

	/**
	 * @param ae
	 * @return
	 */
	private AbstractExtension getRootExtension(AbstractExtension ae) {
		if (ae==null || ae.eContainer() == null || !(ae.eContainer() instanceof EventBElement)) return null;
		if (ae.eContainer() instanceof Machine) return ae;
		return getRootExtension((AbstractExtension) ((EventBElement)ae.eContainer()).getContaining(CorePackage.Literals.ABSTRACT_EXTENSION));
	}

}
