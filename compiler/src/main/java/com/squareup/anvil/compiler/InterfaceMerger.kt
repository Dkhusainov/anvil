package com.squareup.anvil.compiler

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.EffectiveVisibility.Public
import org.jetbrains.kotlin.descriptors.effectiveVisibility
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.constants.ArrayValue
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.types.KotlinType

/**
 * Finds all contributed component interfaces and adds them as super types to Dagger components
 * annotated with `@MergeComponent` or `@MergeSubcomponent`.
 */
internal class InterfaceMerger(
  private val classScanner: ClassScanner
) : SyntheticResolveExtension {
  override fun addSyntheticSupertypes(
    thisDescriptor: ClassDescriptor,
    supertypes: MutableList<KotlinType>
  ) {
    val mergeAnnotation = thisDescriptor.annotationOrNull(mergeComponentFqName)
        ?: thisDescriptor.annotationOrNull(mergeSubcomponentFqName)
        ?: thisDescriptor.annotationOrNull(mergeInterfacesFqName)

    if (mergeAnnotation == null) {
      super.addSyntheticSupertypes(thisDescriptor, supertypes)
      return
    }

    val scope = mergeAnnotation.scope(thisDescriptor.module)

    if (!DescriptorUtils.isInterface(thisDescriptor)) {
      throw AnvilCompilationException(thisDescriptor, "Dagger components must be interfaces.")
    }

    val classes = classScanner
        .findContributedClasses(
            module = thisDescriptor.module,
            packageName = HINT_CONTRIBUTES_PACKAGE_PREFIX,
            annotation = contributesToFqName,
            scope = scope.fqNameSafe
        )
        .filter {
          DescriptorUtils.isInterface(it) && it.annotationOrNull(daggerModuleFqName) == null
        }
        .mapNotNull {
          val contributeAnnotation =
            it.annotationOrNull(contributesToFqName, scope = scope.fqNameSafe)
                ?: return@mapNotNull null
          it to contributeAnnotation
        }
        .onEach { (classDescriptor, _) ->
          if (classDescriptor.effectiveVisibility() !is Public) {
            throw AnvilCompilationException(
                classDescriptor,
                "${classDescriptor.fqNameSafe} is contributed to the Dagger graph, but the " +
                    "interface is not public. Only public interfaces are supported."
            )
          }
        }

    val replacedClasses = classes
        .flatMap { (classDescriptor, contributeAnnotation) ->
          contributeAnnotation.replaces(classDescriptor.module)
              .asSequence()
              .onEach { classDescriptorForReplacement ->
                // Verify the other class is an interface. It doesn't make sense for a contributed
                // interface to replace a class that is not an interface.
                if (!DescriptorUtils.isInterface(classDescriptorForReplacement)) {
                  throw AnvilCompilationException(
                      classDescriptor,
                      "${classDescriptor.fqNameSafe} wants to replace " +
                          "${classDescriptorForReplacement.fqNameSafe}, but the class being " +
                          "replaced is not an interface."
                  )
                }
              }
              .map { it.fqNameSafe }
        }
        .toSet()

    val excludedClasses = (mergeAnnotation.getAnnotationValue("exclude") as? ArrayValue)
        ?.value
        ?.map {
          it.getType(thisDescriptor.module)
              .argumentType()
              .classDescriptorForType()
        }
        ?.filter { DescriptorUtils.isInterface(it) }
        ?.map { it.fqNameSafe }
        ?: emptyList()

    if (excludedClasses.isNotEmpty()) {
      val intersect = supertypes.getAllSuperTypes()
          .toList()
          .intersect(excludedClasses)

      if (intersect.isNotEmpty()) {
        throw AnvilCompilationException(
            classDescriptor = thisDescriptor,
            message = "${thisDescriptor.name} excludes types that it implements or extends. " +
                "These types cannot be excluded. Look at all the super types to find these " +
                "classes: ${intersect.joinToString()}"
        )
      }
    }

    val contributedClasses = classes
        .map { it.first }
        .filterNot {
          val fqName = it.fqNameSafe
          replacedClasses.contains(fqName) || excludedClasses.contains(fqName)
        }
        // Avoids an error for repeated interfaces.
        .distinctBy { it.fqNameSafe }
        .map { it.defaultType }

    supertypes += contributedClasses
    super.addSyntheticSupertypes(thisDescriptor, supertypes)
  }
}
