/**
 * @file MovimentSystem.java 
 * @brief Este modulo trata dos Movimentos jogo bomberman,  
 * criado pelo grupo 6 da turma de programacao sistematica 2-2014 ministrada pela professora genaina
 *
 * @author Igor Chaves Sodre
 * @author Pedro Borges Pio
 * @author Kilmer Luiz Aleluia
 * @since 01/10/2014
 * @version 1.1
 */
package br.unb.unbomber.systems;

import java.util.List;

import net.mostlyoriginal.api.event.common.EventManager;
import net.mostlyoriginal.api.event.common.Subscribe;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import br.unb.gridphysics.GridDisplacement;
import br.unb.gridphysics.MovementCalc;
import br.unb.gridphysics.Vector2D;
import br.unb.unbomber.component.Direction;
import br.unb.unbomber.component.Movable;
import br.unb.unbomber.component.MovementBarrier;
import br.unb.unbomber.component.MovementBarrier.MovementBarrierType;
import br.unb.unbomber.component.Position;
import br.unb.unbomber.event.MovedEntityEvent;
import br.unb.unbomber.event.MovementCommandEvent;

import com.artemis.Aspect;
import com.artemis.ComponentMapper;
import com.artemis.Entity;
import com.artemis.EntitySystem;
import com.artemis.annotations.Wire;
import com.artemis.utils.ImmutableBag;

@Wire
public class MovementSystem extends EntitySystem {

	/** will be injected */
	GridSystem gridSystem;
	
	/** used to access components */
	ComponentMapper<Position> positionMapper;
	
	ComponentMapper<Movable> movableMapper;
	
	ComponentMapper<MovementBarrier> barrierMapper;
	
	
	/** used to dispatch events */
	 EventManager em;
	 
	protected Logger LOGGER = Logger.getLogger("br.unb.unbomber.systems");
	
	
	public MovementSystem(Aspect aspect) {
		super(aspect);
	}

	public MovementSystem() {
		super(Aspect.getAspectForAll(Position.class, Movable.class));
	}


	@Override
	protected void processEntities(ImmutableBag<Entity> entities) {
		// TODO Auto-generated method stub
		
	}
	
	
	/**
	 * Look for new Movement Commands and create their equivalent MovedEntityEvent.
	 * It do not update the entity placement. Think in MovedEntityEvent as a 
	 * intention of movement.
	 */
	@Subscribe
	public void handle(MovementCommandEvent command){
	
		Movable moved = movableMapper.get(command.getEntity());	
		Position originCell = positionMapper.get(command.getEntity());

		
		Vector2D<Integer> originCellIndex = originCell.getIndex();
		
		/** Calculate the displacement and new cell*/
		
		Direction movementDirection = command.getDirection();
		
		Vector2D<Float> displacement = MovementCalc.displacement(
				movementDirection, moved.getSpeed());

		Vector2D<Float> origPosition = moved.getCellPosition();
				
		/** This is a Vector with the final point */
		Vector2D<Float> dest = origPosition.add(displacement);

		Vector2D<Integer> crossVector = MovementCalc.getCrossVector(origPosition, dest);

		Vector2D<Integer> barriers = lookAhead(originCellIndex, crossVector);
		
		displacement = restrictUpdate(origPosition, displacement, barriers);

		//TODO handle grid wrapper. If an entity get to the a border should reenter in another
		
		/** calculate the displacement */
		GridDisplacement gridDisplacement = MovementCalc.gridDisplacement(moved.getCellPosition(), displacement);
		
		Vector2D<Integer> destCellIndex = originCellIndex.add(gridDisplacement.getCells());
		Vector2D<Float> destCellInternalPosition = gridDisplacement.getPosition();
		
		/** fire an event so the collision system can check this movement */		
		MovedEntityEvent movedEntity = new MovedEntityEvent();
		movedEntity.setSpeed(moved.getSpeed());
		movedEntity.setMovedEntity(command.getEntity());
		movedEntity.setDestinationCell(destCellIndex);		
		movedEntity.setDisplacement(destCellInternalPosition);
		em.dispatch(movedEntity);
	}
	
	@Subscribe
	private void handle(MovedEntityEvent movement) {
		
		/** Update Cell Relative Position */	
		Movable movable = movableMapper.get(movement.getMovedEntity());
		
		updateEntity(movement.getMovedEntity(), movement.getDestinationCell(), movement.getDisplacement());
		
		LOGGER.log(Level.DEBUG, "entity moved to" + movable.getCellPosition());
		
	}
	
	void updateEntity(Entity entity, Vector2D<Integer> cell, Vector2D<Float> cellPosition) {
		
		Position postion = positionMapper.get(entity);
		postion.setIndex(cell);
		
		Movable movable = movableMapper.get(entity);
		movable.setCellPosition(cellPosition);
		
	}
	
	/**
	 * Return an Vector. 0 in a dimension mean that this dimention is free.
	 * 1 mean it it blocked.
	 * 
	 * 
	 * @param gridPosition
	 * @param displacement
	 * @return
	 */
	public Vector2D<Integer> lookAhead( Vector2D<Integer> gridPosition, Vector2D<Integer> displacement){
				
		/** in principle, the path is free, no restriction in x or y */
		Vector2D<Integer> restrictions = new  Vector2D<Integer>(0,0);

		

		for(Vector2D<Integer> displacementComponent:displacement.components()){
			Vector2D<Integer> toLookCell = gridPosition.add(displacementComponent);
			
			//TODO create a index and turn it more efficient
			
			List<Entity> entitiesAtCell = gridSystem.getInPosition(toLookCell);
			
			if(entitiesAtCell == null || entitiesAtCell.isEmpty()){
				continue;
			}else{
				for(Entity e: entitiesAtCell){
					MovementBarrier barrier = barrierMapper.get(e);

					if(barrier!=null 
							&& barrier.getType() == MovementBarrierType.BLOCKER){
						restrictions = restrictions.add(displacementComponent);
						break;
					}

				}
			}

		}
		return restrictions;
	}
	

	/**
	 * Return a restricted displacement.
	 * 
	 *  0 = actual + restrictedDisplacement
	 *  rDisplacement = (0.5 - actual) so the entity don't pass half a cell
	 * towards a BLOCK.
	 * 
	 * @param actual
	 * @param displacement
	 * @param barriers
	 * @return
	 */
	protected Vector2D<Float> restrictUpdate(Vector2D<Float> actual, Vector2D<Float> displacement,
			Vector2D<Integer> barriers) {
		
		final Vector2D<Float> HALF_CELL = new Vector2D<Float>(0.5f, 0.5f);
		/* invert barriers, xor (1,1) */
		Vector2D<Integer> modBarriers =  barriers.xor(new Vector2D<>(0,0));
		
		/* invert barriers, xor (1,1) */
		Vector2D<Integer> invertedBarriers =  modBarriers.xor(new Vector2D<>(1,1));

		/* do restrict to actual complement */
		Vector2D<Float> restrictedDisplacement = modBarriers.toFloatVector().mult(HALF_CELL.sub(actual));
		
		/* filter not restricted */
		Vector2D<Float> notRestrictedDisplacement =  invertedBarriers.toFloatVector().mult(displacement);
		
		/* unify the restricted and not restricted */
		return notRestrictedDisplacement.add(restrictedDisplacement);
		
	}
}