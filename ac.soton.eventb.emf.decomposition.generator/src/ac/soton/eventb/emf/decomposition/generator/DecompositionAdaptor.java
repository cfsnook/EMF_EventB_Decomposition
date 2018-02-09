/*******************************************************************************
 *  Copyright (c) 2016-2017 University of Southampton.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *   
 *  Contributors:
 *  University of Southampton - Initial implementation
 *******************************************************************************/
package ac.soton.eventb.emf.decomposition.generator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.transaction.TransactionalEditingDomain;
import org.eventb.emf.core.CorePackage;
import org.eventb.emf.core.EventBNamed;
import org.eventb.emf.core.EventBNamedCommentedComponentElement;
import org.eventb.emf.core.context.Context;
import org.eventb.emf.core.machine.Machine;

import ac.soton.emf.translator.TranslationDescriptor;
import ac.soton.emf.translator.eventb.adapter.EventBTranslatorAdapter;
import ac.soton.eventb.emf.inclusion.InclusionPackage;
import ac.soton.eventb.emf.inclusion.MachineInclusion;


/**
 * This implementation of IAdapter extends the EventBTranslatorAdapter as follows:
 * 
 * getComponentURI - add support for composition machines
 * getAffectedResources = also returns composition machines
 * 
 * @author cfs
 *
 */

public class DecompositionAdaptor extends EventBTranslatorAdapter {


	/**
	 * returns a URI for..
	 *  a Rodin machine  or..
	 *  a Rodin context  or..
	 *  , for the composition machine, an EMF serialisation of the machine
	 * 
	 */
	@Override
	public URI getComponentURI(TranslationDescriptor translationDescriptor, EObject rootElement) {
		if (rootElement instanceof EObject && translationDescriptor.remove == false && 
				translationDescriptor.feature == CorePackage.Literals.PROJECT__COMPONENTS &&
				translationDescriptor.value instanceof EventBNamedCommentedComponentElement){
			String projectName = EcoreUtil.getURI((EObject) rootElement).segment(1);
			URI projectUri = URI.createPlatformResourceURI(projectName, true);
			String fileName = ((EventBNamed)translationDescriptor.value).getName();
			String ext = 
				isCompositionMachine(translationDescriptor.value) ? Activator.compositionMachineExtension :
				translationDescriptor.value instanceof Context ? Activator.contextExtension :
				translationDescriptor.value instanceof Machine ? Activator.machineExtension:
					Activator.defaultExtension;
			URI fileUri = projectUri.appendSegment(fileName).appendFileExtension(ext); //$NON-NLS-1$
			return fileUri;
		}
		return null;
	}
	
	/**
	 * determines whether this value is a composition machine
	 * (i.e. a machine with includes clauses)
	 * @param value
	 * @return
	 */
	private boolean isCompositionMachine(Object value) {
		if (value instanceof Machine){
			for (EObject mi : ((Machine)value).getAllContained(InclusionPackage.Literals.MACHINE_INCLUSION,false)){
				if (mi instanceof MachineInclusion){
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @see ac.soton.emf.translator.configuration.DefaultAdapter#getAffectedResources(org.eclipse.emf.transaction.TransactionalEditingDomain, org.eclipse.emf.ecore.EObject)
	 * 
	 * This implementation returns all EMF resources in the same project as the source element that are 
	 * EventB Machines or Contexts, composition machines or xmi formated files
	 * 
	 * @param editingDomain
	 * @param sourceElement
	 * @return list of affected Resources
	 */
	public Collection<Resource> getAffectedResources(TransactionalEditingDomain editingDomain, EObject sourceElement) throws IOException {
		List<Resource> affectedResources = new ArrayList<Resource>();
		String projectName = EcoreUtil.getURI(sourceElement).segment(1);
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
		if (project.exists()){
			try {
				IResource[] members = project.members();
				ResourceSet resourceSet = editingDomain.getResourceSet();
				for (IResource res : members){
					final URI fileURI = URI.createPlatformResourceURI(projectName + "/" + res.getName(), true);
					if (Activator.machineExtension.equals(fileURI.fileExtension()) ||
						Activator.contextExtension.equals(fileURI.fileExtension()) ||
						Activator.compositionMachineExtension.equals(fileURI.fileExtension()) ||
						Activator.defaultExtension.equals(fileURI.fileExtension()) ) 
					{ 
						Resource resource = resourceSet.getResource(fileURI, false);
						if (resource != null) {
							if (!resource.isLoaded()) {
								resource.load(Collections.emptyMap());
							}
							if (resource.isLoaded()) {
								affectedResources.add(resource);
							} 
						}
					}
				}
			} catch (CoreException e) {
				e.printStackTrace();
			}
		}
		return affectedResources;
	}

}
