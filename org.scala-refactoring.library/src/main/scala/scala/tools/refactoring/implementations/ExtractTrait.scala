package scala.tools.refactoring
package implementations

import scala.reflect.generic.Flags
import scala.tools.refactoring.MultiStageRefactoring

import common.Change

abstract class ExtractTrait extends MultiStageRefactoring with common.InteractiveScalaCompiler with analysis.Indexes with ImportsHelper {
  self =>
  

  import global._
  
  case class PreparationResult(classDef: ClassDef, extractableMembers: List[String])
  
  case class RefactoringParameters(traitName: String, defFilter: String => Boolean)
  
  override def prepare(s: Selection) = {
    def isExtractable(valOrDefDef: ValOrDefDef) = {
      val mods = valOrDefDef.mods
      !(mods.isParamAccessor || mods.isPrivate || 
          (valOrDefDef.hasSymbol && valOrDefDef.symbol.isConstructor))
    }
    
    s.findSelectedOfType[ClassDef] match {
      case None => Left(PreparationError("no class def selected"))
      case Some(classDef) => {
        val extractableMembers = classDef.impl.body.collect {
          case valOrDefDef: ValOrDefDef if isExtractable(valOrDefDef) => valOrDefDef.nameString
        }
        Right(PreparationResult(classDef, extractableMembers))
      }
    }
  }
  
  override def perform(selection: Selection, prep: PreparationResult, params: RefactoringParameters): Either[RefactoringError, List[Change]] = {
    
    def getsExtracted(defFilter: String => Boolean)(tree: Tree) = tree match {
      case valOrDefDef: ValOrDefDef if !valOrDefDef.mods.isParamAccessor => defFilter(valOrDefDef.nameString stripSuffix "_=")
      case _ => false
    }
  
    val classDef = prep.classDef
    
    val (traitBody, classBody) = prep.classDef.impl.body partition getsExtracted(params.defFilter)
    
    def symbols(trees: List[Tree]) = trees collect { case t: Tree if t.hasSymbol => t.symbol}
    
    val classMembers = symbols(classBody)
    val traitMembers = symbols(traitBody)
    val arePrivateClassMembersAccessed = areSymbolsAccessed(traitBody, classMembers.filter(_.isPrivate))
    val arePrivateTraitMembersAccessed = areSymbolsAccessed(classBody, traitMembers.filter(_.isPrivate))
    val isExtractionWellDefined = !arePrivateClassMembersAccessed && !arePrivateTraitMembersAccessed
    if(!isExtractionWellDefined) {
      return Left(RefactoringError("can't reference private class members from extracted trait or private trait members from class"))
    }
    
    val enclosingPackages = findEnclosingPackages(classDef.pos)
    
    val sourceFileChanges = refactorClass(classBody, enclosingPackages, params.traitName, classDef)
    
    val traitNeedsSelfType = areSymbolsAccessed(traitBody, classMembers.filter(!_.isPrivate))
    val traitFileChanges = refactorTrait(traitBody, traitNeedsSelfType, enclosingPackages, params.traitName, classDef)
    
    Right(sourceFileChanges:::traitFileChanges)
  }
  
  private def refactorClass(classBody: List[Tree], enclosingPackages: List[PackageDef], traitName: String, classDef: ClassDef) = {
    val addTrait = {
      val extractedTrait = mkIdent(traitName, classDef.tparams)
      transform {
        case t @ Template(parents, self, body) => {
          val impl = Template(parents:::List(extractedTrait), self, classBody)
          impl replaces t 
        }
      }
    }
    
    val templateFilter = filter {
      case classDef.impl => true
    }
    
    
    
    val classRefactoring = topdown {
      matchingChildren {
        templateFilter &> addTrait
      }
    } &> addRequiredImports(None, None)
    
    refactor(classRefactoring(enclosingPackages.head).toList)
  }
  
  private def refactorTrait(traitBody: List[Tree], needsSelfTypeAnnotation: Boolean, enclosingPackages: List[PackageDef], traitName: String, classDef: ClassDef) = {
    val traitRefactoring = {
      transform {
        case pkg: PackageDef => {
          val imports = pkg.stats.collect {case importTree: Import => importTree}
          val selfType = if(needsSelfTypeAnnotation) {
            val classIdent = mkIdent(classDef.nameString, classDef.tparams)
            ValDef(Modifiers(Flags.SYNTHETIC), "this", CompoundTypeTree(Template(List(classIdent), emptyValDef, Nil)), EmptyTree)
          } else {
            emptyValDef
          }
          val impl = Template(Nil, selfType, traitBody)
          val mods = Modifiers(Flags.TRAIT).withPosition(Flags.TRAIT, NoPosition)
          val extractedTrait = ClassDef(mods, newTypeName(traitName), classDef.tparams, impl)
          val flattenedPkg = PackageDef(flattenPkgPids(enclosingPackages), imports:::List(extractedTrait))
          flattenedPkg replaces pkg
        }
      } &> addRequiredImports(None, None)
    }
    
    refactor(traitRefactoring(enclosingPackages.head).toList).map(_.toNewFile(""))
  }
  
  private def areSymbolsAccessed(accessors: List[Tree], targets: List[Symbol]) = {
    def collectReferences(trees: List[Tree], symbols: List[Symbol]) = {
      def symbolsFilter(symbols: List[Symbol])(tree: Tree): Boolean = tree match {
        case tree: Tree if tree.hasSymbol => symbols contains tree.symbol
        case _ => false
      }
      val symbolsTraverser = new FilterTreeTraverser(symbolsFilter(symbols))
      symbolsTraverser.traverseTrees(trees)
      val hits = symbolsTraverser.hits.toList
      hits
    }
    
    val references = collectReferences(accessors, targets)
    
    !references.isEmpty
  }
  
  private def flattenPkgPids(packages: List[PackageDef]) = {
    
    def flattenPkgPidsHelper(inner: Tree, outer: Tree): Select = inner match {
      case Ident(name) => Select(outer, name)
      case Select(qualifier, name) => Select(flattenPkgPidsHelper(qualifier, outer), name)
      case _ => throw new Exception("unknown pid tree")
    }

    val flattenedPkgPid = packages match {
      case Nil => Ident("")
      case pkg :: pkgs => 
        // Copy the tree so it doesn't drag layout from the original file to the new one.
        val newPid = shallowDuplicate(pkg.pid) setPos NoPosition
        pkgs.foldLeft(newPid: RefTree)((outer, innerPkg) => flattenPkgPidsHelper(innerPkg.pid, outer))
    }
    
    flattenedPkgPid
  }
  
  private def findEnclosingPackages(pos: Position) = {
    val cu = cuRoot(pos).get
    def enclosingPackageFilter(tree: Tree): Boolean = tree match {
      case pkg @ PackageDef(pid, stats) => {
        pkg.pos.properlyIncludes(pos)
      }
      case _ => false
    }
    val traverser = new FilterTreeTraverser(enclosingPackageFilter)
    traverser.traverse(cu)
    traverser.hits.toList collect {
      case pkg: PackageDef => pkg
    }
  }
  
  private def mkIdent(name: String, typeParams: List[TypeDef]) = {
    val tparams = typeParams match {
      case Nil => ""
      case ts => "[" + ts.map(_.nameString).mkString(", ") + "]"
    }
    Ident(newTermName(name + tparams))
  }
  
}
